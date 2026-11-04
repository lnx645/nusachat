# NusaChat Realtime (Erlang/OTP) — satu-satunya gateway WS

Gateway WebSocket di port **8091** yang mengantar seluruh fitur realtime:
chat (`message`, `read`, `typing`, `revoked`, `edited`, `reaction`),
panggilan (`call_offer/answer/ice/end`, room), pertemanan, momen/story,
wallet, presence, dan lokasi live. Gateway PHP lama sudah **dihapus total**.

Kontrak: PHP tetap sumber kebenaran dan **menulis** outbox
(`realtime_events` di MySQL); node ini **menyalurkan** ke socket per user.
Tanpa polling: tiap `Realtime_model::push()` langsung POST baris baru ke
`POST /internal/notify` (header `x-ws-secret`), lalu node fan-out seketika.
Cursor hanya dibaca sekali saat boot (catch-up) dan disimpan ke disk
(`REALTIME_DATA_DIR/nusa_cursor`, default `/tmp/nusa_realtime/`) tiap
10 detik + saat shutdown, sehingga restart mengirim ulang bukan
menghilangkan. Prune baris > 24 jam jalan tiap 5 menit (maintenance);
tick yang sama menyapu antrean relay (`messages` kedaluwarsa +
penerima/reaksi/pin/bintang/sembunyinya). Interval via
`NUSA_PRUNE_MS` (default 300000, min 1000 — untuk test).

## Anatomi 10 menit (baca ini dulu)

Satu pesan chat realtime mengalir begini:

```text
Android --REST--> PHP : tulis DB + tulis outbox --POST /internal/notify--> Erlang
Erlang: nusa_notify_handler -> nusa_fanout -> nusa_registry (cari socket
user) -> kirim JSON ke tiap socket --WS--> Android tujuan
```

Pohon supervisor (siapa mengawasi siapa — crash satu anak me-restart
anak itu saja, yang lain tidak ikut mati):

```text
nusachat_realtime_sup (one_for_one)
├── nusa_mysql      koneksi MySQL tunggal + retry
├── nusa_registry   ETS user_id -> [socket pid], monitor DOWN
├── nusa_nearby     lokasi live + subscriber nearby
├── nusa_fanout     antar event + cursor + prune
└── nusa_gateway    listener Cowboy (terakhir: buka port bila semua siap)
```

Koneksi WS: `nusa_ws_handler` per socket (bukan gen_server — proses
cowboy biasa): auth JWT saat handshake → register ke registry →
ping 25 dtk → terima `{deliver, JSON}` dari fanout/nearby → tulis ke
socket → unregister saat tutup.

## Peta modul (src/ datar = idiom OTP; lapisan via penamaan)

```text
Transport (bicara Cowboy/HTTP):
  nusa_ws_handler      handshake JWT ?token=, connected/ping-pong, slot nearby.*
  nusa_notify_handler  POST /internal/notify dari PHP (cek x-ws-secret)
Core (logika, tanpa tahu HTTP):
  nusa_fanout          antar event outbox ke socket + cursor + prune
  nusa_registry        direktori socket per user (ETS + monitor)
  nusa_nearby          orang-terdekat live (ETS lokasi + persist + push delta)
  nusa_auth            verifikasi token = nusa_jwt + cek user MySQL
Murni (tanpa I/O, di-unit-test):
  nusa_jwt             verifikasi signature HS256 + klaim (typ/sub/tv/exp)
  nusa_envelope        amplop kabel v1 {"event_id","type","data","ts"}
  nusa_geo             validasi koordinat, clamp radius, haversine
Infra:
  nusa_mysql           satu koneksi mysql-otp, retry 4x lalu {error,...}
  nusa_config          env var (aturan: OS env menang, else default dev)
  nusa_gateway         pemilik listener Cowboy di bawah supervisor
```

Aturan dependensi (jangan dilanggar): transport boleh panggil core;
core boleh panggil infra; modul murni tidak boleh panggil siapa pun;
tidak ada yang panggil transport kecuali supervisor. Kalau ragu,
lihat `-spec` di tiap fungsi export — itulah kontraknya.

Bentuk kabel v1 (`nusa_envelope`, dipakai Android — ubah hanya dengan
versionalisasi):

```json
{"event_id":123,"type":"message","data":{...},"ts":"2026-..Z"}
```

## Perintah harian

```sh
cd realtime/nusachat_realtime
rebar3 compile    # kompilasi
rebar3 eunit      # 22 unit test modul murni (jwt/geo/envelope/fanout)
rebar3 dialyzer   # cek tipe statis (PLT di-cache, run pertama lambat)
```

Menjalankan (dev):

```sh
DB_USER=nusa DB_PASS='<isi>' REALTIME_PORT=8091 \
  erl -noshell -pa _build/default/lib/*/ebin \
  -eval 'application:ensure_all_started(nusachat_realtime), receive after infinity -> ok end.'
```

`APP_SECRET` harus sama dengan `backend/.env` (verifikasi JWT).
`WS_SECRET` harus sama dengan `.env` (auth `/internal/notify`).
Verifikasi end-to-end (18/18 hijau):

```sh
python3 backend/tools/ws_smoke.py http://127.0.0.1:8080/api/v1 ws://127.0.0.1:8091
```

## Buku resep (tambah X tanpa baca semua kode)

**Tambah event WS baru** (mis. `wallet.paid`): tidak perlu sentuh Erlang
sama sekali — cukup `Realtime_model::push($ids, 'wallet.paid', $data)` di
PHP. Fan-out generik by `user_id`.

**Tambah slot pesan client→server** (seperti `nearby.*`): tambah klausa di
`nusa_ws_handler:websocket_handle` + logika di modul core baru/lama.
Jangan taruh query MySQL di handler — panggil modul core.

**Ganti query MySQL**: hanya lewat `nusa_mysql:query/2`. Jangan buka
koneksi sendiri; satu koneksi milik bersama + retry sudah diurus.

**Baca log**: `error_logger:info_msg` (boot, cursor, prune) dan
`error_msg` (koneksi DB gagal). Di dev: `/tmp/erlang.log`.

**Restart aman**: cursor tersimpan tiap 10 dtk + saat shutdown tertib
(`terminate`). Kill -9 paling buruk = redeliver ≤10 dtk terakhir
(klien dedup by `event_id`).

## Kamus OTP mini (untuk yang belum pernah Erlang)

- **application**: unit start/stop (`nusachat_realtime_app`). Analogi:
  `main()` + lifecycle.
- **supervisor**: proses yang tugasnya cuma "awasi anak, restart bila
  mati" (`nusachat_realtime_sup`). Analogi: systemd di dalam aplikasi.
  `one_for_one` = anak lain tidak ikut restart.
- **gen_server**: proses dengan API request/reply (`call`, sync) dan
  pesan api (`cast`, async). Analogi: service class dengan mailbox.
  `handle_call/handle_cast/handle_info` = tiga pintu masuk pesan.
- **ETS**: key-value in-memory (`nusa_registry`, tabel lokasi).
  Analogi: `ConcurrentHashMap` global per node.
- **monitor/DOWN**: langganan "kabari saya bila proses X mati".
  Dipakai registry + nearby agar socket putus otomatis dibersihkan
  walau `terminate` tak terpanggil.
- **`-spec`**: deklarasi tipe fungsi. Dicek `rebar3 dialyzer` (mirip
  mypy/PHPStan, tapi tanpa anotasi di badan fungsi).
- **eunit**: unit test (`test/*_tests.erl`, `rebar3 eunit`). Hanya
  untuk modul murni — yang butuh MySQL dites via `ws_smoke.py`.

## Konfigurasi (env, default cocok dengan `backend/.env`)

| Var | Default | Arti |
| --- | ------- | ---- |
| `REALTIME_PORT` | `8091` | Port listen gateway |
| `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS` | `127.0.0.1`/`3306`/`nusachat`/`nusa`/kosong | MySQL (wajib) |
| `APP_SECRET` | sama dengan `.env` | Verifikasi JWT HS256 |
| `WS_SECRET` | `dev-ws-secret` | Auth `/internal/notify` dari PHP |
| `REALTIME_DATA_DIR` | `/tmp/nusa_realtime` | Direktori file cursor |

## Migrasi SQLite -> MySQL (sudah dilakukan sekali, arsip)

1. `apt install mariadb-server`, buat DB `nusachat` + user `nusa`.
2. Import `backend/database/schema.mysql.sql`.
3. Salin data per tabel dengan id asli (`FOREIGN_KEY_CHECKS=0`).
4. `DB_DRIVER=mysql` di `backend/.env`, restart REST (8080) + Erlang
   (8091), lalu `tools/smoke.py` dan `tools/ws_smoke.py` harus hijau.

Catatan reboot: MariaDB + Erlang perlu dinyalakan ulang
(`service mariadb start` + perintah `erl` di atas).
