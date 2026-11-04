# NusaChat Realtime (Erlang/OTP)

Gateway WebSocket yang mengantar seluruh fitur realtime aplikasi:
chat (`message`, `read`, `typing`, `revoked`, `edited`, `reaction`, ...),
panggilan (`call_offer`, `call_answer`, `call_ice`, `call_end`, room),
pertemanan, momen/story, wallet, dan presence. Wire-compatible dengan
gateway PHP lama (`backend/tools/ws-server.php`) — client tidak berubah.

Kontrak: PHP tetap sumber kebenaran dan **menulis** outbox
(`realtime_events` di MySQL); node ini **menyalurkan** ke socket per user.

Tanpa polling: setiap `Realtime_model::push()` langsung POST baris baru ke
`POST /internal/notify` (header `x-ws-secret`, body `{events: [...]}`),
lalu node fan-out seketika (terukur 10-16 ms). Tidak ada timer poll;
cursor hanya dibaca sekali saat boot (catch-up) dan disimpan ke disk
(`REALTIME_DATA_DIR/nusa_cursor`, default `/tmp/nusa_realtime/`) tiap
10 detik + saat shutdown, sehingga restart mengirim ulang bukan
menghilangkan. Prune 24 jam tetap jalan tiap 5 menit (maintenance).

## Struktur standar rebar3/OTP

```text
realtime/nusachat_realtime/
├── rebar.config                 # deps: cowboy (WS), mysql-otp, jsx
├── src/nusachat_realtime.app.src
├── src/nusachat_realtime_app.erl # application callback
├── src/nusachat_realtime_sup.erl # top supervisor (one_for_one)
├── src/nusa_config.erl          # env: DB_*, APP_SECRET, REALTIME_PORT
├── src/nusa_auth.erl            # JWT HS256 ?token= (typ/sub/tv/exp)
├── src/nusa_mysql.erl           # pemilik koneksi mysql-otp + retry
├── src/nusa_registry.erl        # ETS user_id -> [ws pid], monitor DOWN
├── src/nusa_poller.erl          # fan-out push + cursor persisten + prune 24 jam
├── src/nusa_notify_handler.erl  # POST /internal/notify dari PHP
├── src/nusa_ws_handler.erl      # cowboy_websocket: connected/ping-pong/fan-out
├── src/nusa_nearby.erl          # orang terdekat live: ETS lokasi + persist MySQL + push delta
└── src/nusa_gateway.erl         # pemilik listener Cowboy (di bawah supervisor)
```

Pola OTP yang dipakai: `application` -> `supervisor` ->
`gen_server` (`nusa_mysql`, `nusa_registry`, `nusa_poller`, `nusa_gateway`)
+ `cowboy_websocket` handler. Satu koneksi MySQL milik `nusa_mysql`
(reconnect otomatis); pengiriman per user lewat `nusa_registry`.

## Konfigurasi (env, default cocok dengan `backend/.env`)

| Var | Default | Arti |
| --- | ------- | ---- |
| `REALTIME_PORT` | `8091` | Port listen gateway Erlang |
| `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS` | `127.0.0.1`/`3306`/`nusachat`/`nusa`/kosong | MySQL (wajib; lihat Migrasi) |
| `APP_SECRET` | sama dengan `.env` | Verifikasi JWT HS256, harus sama dengan PHP |

## Menjalankan

```sh
cd realtime/nusachat_realtime
rebar3 compile
DB_USER=nusa DB_PASS='<isi>' REALTIME_PORT=8091 \
  erl -noshell -pa _build/default/lib/*/ebin \
  -eval 'application:ensure_all_started(nusachat_realtime), receive after infinity -> ok end.'
```

Di mesin dev, node jalan persisten via respawn loop (log `/tmp/erlang.log`),
port **8091**, aplikasi Android menunjuk ke `ws://<ip>:8091`.
Gateway PHP di 8090 tetap jalan sebagai fallback.

## Verifikasi

```sh
# 18/18 harus hijau (handshake 401, connected, ping/pong, message + call signaling
# + nearby live: location.update -> nearby.result, nearby.changed push, invalid ditolak,
#   multi-kandidat terurut)
python3 backend/tools/ws_smoke.py http://127.0.0.1:8080/api/v1 ws://127.0.0.1:8091
```

## Migrasi SQLite -> MySQL (sudah dilakukan sekali)

1. `apt install mariadb-server`, buat DB `nusachat` + user `nusa`.
2. Import `backend/database/schema.mysql.sql` (abaikan error
   `Duplicate column` pada ALTER kompatibilitas — kolomnya memang sudah ada).
3. Salin data per tabel dengan id asli (`FOREIGN_KEY_CHECKS=0`), samakan
   jumlah baris SQLite vs MySQL, lalu `DB_DRIVER=mysql` di `backend/.env`.
4. Restart REST (8080) + WS PHP (8090), lalu `tools/smoke.py` harus 133/133.

Catatan reboot: MariaDB + Erlang + loop PHP perlu dinyalakan ulang
(`service mariadb start`, loop di `/tmp/*.loop` tidak persisten).
