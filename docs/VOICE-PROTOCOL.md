# Protokol Voice NusaChat v1 (NVP/1 + `nusa.call.v1`)

Tujuan: voice call 1-1 dan room grup dengan **bandwidth minimum absolut**,
**tahan sadap**, dan **jalan di jaringan jelek** — tanpa WebRTC.
Media relay via Erlang (tembus NAT/hotspot), signaling via WebSocket Erlang.

Status: v1 voice-only. Path lama (REST `/calls/*` + WebRTC, dipakai video)
tetap utuh sebagai fallback dan kompatibilitas.

## 1. Arsitektur

```
HP A ←—WS signaling (TCP 8091)—→ Erlang ←—WS—→ HP B
HP A ←—UDP media (8092, relay)—→ Erlang ←—UDP—→ HP B
PHP: riwayat/incoming/push + fallback REST (tak ada di hot path)
```

Aturan keras:

- Media TAK PERNAH P2P di v1 (alasan: NAT simetris/hotspot, keseragaman room).
- Relay TIDAK menyimpan/mendekrip suara (teruskan biner buram).
- Semua pesan signaling mengikuti envelope existing
  `{"type": "...", "data": {...}}` dan auth JWT existing (`nusa_ws_handler`).
- Status call TETAP ditulis ke tabel `calls`/`call_participants` yang sama
  (SQL cermin `Call_model`) agar riwayat/incoming/push PHP tidak berubah.

## 2. Signaling `nusa.call.v1` (WS)

### 2.1 Client → server

| Tipe | Field wajib | Aturan |
|---|---|---|
| `call.offer` | `to_user_id`, `codecs[]`, `key_ct`, `salt` | 1-1, kind selalu audio. Cermin validasi `Calls::offer`: callee aktif, bukan diri sendiri, cek blokir 2 arah (`blocks`), throttle 20/mnt. |
| `call.answer` | `call_id`, `accept`, `codec` | Hanya callee. `accept=false` = tolak. |
| `call.reject` | `call_id` | Hanya callee, status masih `ringing`. |
| `call.end` | `call_id`, `reason?` | Peserta call. `reason`: `hangup`/`missed`/`failed`. |
| `call.room_join` | `call_id` | Anggota konversasi; maks 8 joined (asumsi reversibel). |
| `call.room_leave` | `call_id` | Peserta joined. Terakhir keluar = room `ended`. |
| `call.echo` | `call_id`, `on` | Uji 1 HP: relay pantulkan paket pengirim ke dirinya sendiri. |

`codecs[]`: subset `["opus","amr-wb"]` berurutan preferensi.
`key_ct`: kunci media 32B (base64) + `salt` 4B (base64), dibuat penelepon.

### 2.2 Server → client

| Tipe | Ke | Isi pokok |
|---|---|---|
| `call.prepared` | pengirim setup | `call_id`, `slot`, `token` (uint32), `relay:{host,port}` |
| `call.offer` | callee (bila online) | `call_id`, `from`, `codecs`, `key_ct`, `salt` |
| `call.peer_offline` | penelepon | `{to_user_id}` → klien WAJIB fallback REST (push) |
| `call.answer` | penelepon | `call_id`, `from_user_id`, `codec` |
| `call.end` | lawan/anggota | `call_id`, `from_user_id`, `reason` |
| `call.room_joined` / `call.room_left` | anggota lain | `call_id`, user, slot |
| `call.error` | pengirim | `{code, message}` (`not_found`, `forbidden`, `blocked`, `room_full`, `rate_limited`, `invalid_*`) |

Kode error cermin string PHP (`not_found`, `forbidden`, `blocked`, dll).

### 2.3 Alur 1-1

1. A→`call.offer`. Server: validasi → INSERT `calls` (ringing, uuid v4) →
   alokasi slot 0 + token A → jawab `call.prepared` ke A.
2. Callee online → server teruskan `call.offer` (langsung ke socket, TANPA
   baris `realtime_events`; catch-up via REST `incoming`).
   Callee offline → `call.peer_offline` ke A → A fallback REST (push terkirim).
3. B→`call.answer{accept:true,codec}` → server: slot 1 + token B, status
   `active` + `answered_at` → `call.prepared` ke B + `call.answer` ke A.
4. Keduanya kirim paket hello UDP → relay ikat endpoint → media mengalir.
5. Siapa pun →`call.end` → lawan dapat `call.end`, relay purge, status akhir.

Timeout dering 45 detik dimiliki klien penelepon (tanpa timer server di v1).

### 2.4 Alur room

`room_create` tetap REST (cold path). Join/leave/sinyal UI via WS di atas.
Slot 0 = pembuat; join berikutnya slot bebas pertama; cap 8.
Kunci media room deterministik (Erlang = PHP identik):
`key_ct` = b64url(HMAC(APP_SECRET, "nvp-room-key"|call:64)) [32B],
`salt` = b64url(4B pertama HMAC(APP_SECRET, "nvp-room-salt"|call:64)).
Codec room TETAP `amr-wb` (universal, tanpa negosiasi); 1-1 negosiasi
opus↔amr-wb.

## 3. Media NVP/1 (UDP)

### 3.1 Header (18 byte, big-endian)

```
 0:      magic 0x4E
 1:      ver 0x01
 2-5:    call_id uint32
 6:      slot uint8 (0-255)
 7:      flags: bit0=redundansi, bit1=comfort(DTX), bit2=keepalive(tanpa payload),
          bit3=kontrol (payload JSON, §3.4b)
 8-11:   token uint32 = 4 byte pertama HMAC-SHA256(APP_SECRET,
          "nvp-v1" | call_id:64 | user_id:64) — deterministik, dihitung
          identik oleh Erlang dan PHP (uji silang di smoke)
 12-13:  seq uint16 (wrap; dedup penerima)
 14-17:  ts uint32 (clock 16 kHz, wrap ~3 hari)
 +payload + tag GCM 16 byte
```

Total overhead kabel: 34 byte/paket.

### 3.2 Kripto (anti-sadap)

- AES-GCM-256 per paket (`javax.crypto`, API 1+). Kunci 32B per call.
- Nonce 12B = `salt(4) | ver(1) | slot(1) | seq(2) | ts(4)` — direkonstruksi
  dari header + konteks, NOL byte di kabel. Unik per (kunci, paket) karena
  seq/ts monoton per pengirim (syarat keunikan IV-GCM terpenuhi by-construction).
- Header ikut AAD (tidak bisa dipalsukan tanpa merusak tag).
- Model trust v1 (jujur): relay buta isi; kunci didistribusikan via WS
  terotentikasi (server teruskan, singgah di RAM, tak disimpan). E2EE penuh
  (server-buta-kunci via X3DH existing) = v2, non-goal di sini.

### 3.2b Format payload audio (plaintext sebelum seal)

- Bundel per paket: `[n:1B][len:2B,blob]×n`, tiap blob = 1 frame 20 ms
  (Opus/AMR-WB selalu di-encode per 20 ms; n = 1..3 sesuai profil).
- Redundansi (flag bit0): `[prevSamples:2B][prevLen:2B][prevBundel][curBundel]`;
  penerima selipkan frame lalu di `ts - prevSamples` bila pas mengisi gap.
- Kontrol (flag bit3, tak majukan seq/ts audio): JSON UTF-8
  `{"t":"fb","loss":f,"jit":ms,"rtt":ms}` tiap 2 dtk,
  `{"t":"ping","id","ts"}` tiap 5 dtk, `{"t":"pong","id","ts"}` balasan,
  `{"t":"csd","csd":b64}` 3× di awal bicara (Opus saja).
- Handshake CSD: blob codec-config encoder TIDAK PERNAH jadi payload audio;
  pengirim kirim CSD-nya (mis. OpusHead) via kontrol `csd`, penerima configure
  decoder dengannya. Opus menunggu CSD (maks 2 dtk, lalu coba polos);
  AMR-WB tak punya CSD → langsung polos. Terbukti perlu di Infinix X6532
  (decoder Opus tanpa CSD = gagal total).

### 3.3 Codec (risiko fragmentasi → mitigasi probing)

- Utama: Opus (mono 16 kHz). Encoder dijamin Android 10+ (API 29+).
- Fallback: AMR-WB (16 kHz, SEMUA versi Android) — clock sama, pipeline sama.
- Klien probe MediaCodec saat start; lapor `codecs[]` aktual di offer;
  callee pilih irisan pertama. Selalu ada codec (AMR-WB universal).

### 3.4 Profil adaptif (jaringan jelek + bandwidth minimum)

| Profil | Codec/bitrate | Frame | Redundansi | Aktif ≈ | Diam ≈ |
|---|---|---|---|---|---|
| ultra (default) | Opus 8k | 60 ms | 0 | ~12,5 kbps | ~0,3 kbps |
| hemat | Opus 12k | 40 ms | 0 | ~19 kbps | ~0,3 kbps |
| aman | Opus 16k | 40 ms | 1× frame lalu | ~30 kbps | ~0,3 kbps |

Penerima lapor `{loss%, jitter, rtt}` tiap 2 dtk via paket kontrol UDP
(flag keepalive + payload JSON mini); pengirim naik/turun profil.
Jitter buffer adaptif 80–400 ms. DTX total saat diam (hemat ~50%+).
Keepalive tiap 10 dtk (ikat NAT); relay expire sesi 30 dtk.

"Zero bandwidth": mustahil mutlak saat bicara; lantai desain = profil ultra
(~12,5 kbps) dan ~nol saat diam. Klaim "minimum absolut" diukur di smoke
(bawah), bukan slogan.

### 3.5 Aturan relay

- Satu socket `gen_udp` (default UDP 8092, `VOICE_UDP_PORT`; bind 0.0.0.0).
- Slot deterministik (tanpa RPC alokasi): 1-1 → caller=0, callee=1;
  room → indeks user di `call_participants` ORDER BY id ASC (semua status,
  stabil selamanya; total distinct ≤8).
- Token deterministik (§3.1): relay validasi paket pertama tiap (call,slot)
  via DB (resolve slot→user→token ekspektasi), lalu cache endpoint di ETS
  `voice_sessions`; paket berikut cocokkan token cache.
- Per paket: cek ukuran ≤1200 B → token cocok → rate-limit 100 pps/pengirim
  → forward ke slot lain (room: semua; 1-1: lawan; echo: pengirim).
- Rebinding NAT: endpoint di-update bila paket terautentikasi dari alamat baru.
- Purge saat `call.end`/terakhir-leave + sweeper sesi kedaluwarsa (30 dtk).
- Counter byte/paket per call (bukti budget bandwidth di smoke).

## 4. Klien Android (audio saja; path video/WebRTC tak disentuh)

- `NvpPacket.kt` murni: seal/open, nonce turunan, tolak tag/rusak.
  (Unit-test JVM penuh — JCE tersedia di JVM.)
- `NusaVoiceEngine`: AudioRecord 16 kHz mono sumber `VOICE_COMMUNICATION`
  (+ `MODE_IN_COMMUNICATION`, penanganan speaker) → MediaCodec per 20 ms →
  bundel → UDP; terima → decode → jitter PCM → mix → AudioTrack; statistik
  adaptasi + log codec terpilih (mitigasi fragmentasi: kelihatan di log).
- Signaling: WS-first + fallback REST penuh:
  - Offer NVP via REST memakai field `sdp` sebagai kurir:
    `sdp = "nvp1:" + base64(json{codecs,key_ct,salt})` (opaque pass-through).
    Penerima deteksi prefix → jalur NVP; klien lama abaikan (gagal WebRTC).
  - Answer NVP via REST: `sdp = "nvp1:<codec>"`.
  - Slot/token/relay via WS `call.prepared`, atau REST baru
    `POST /calls/nvp_join {call_id}` (hitung slot/token deterministik,
    untuk fallback jawab/ruang saat WS mati). Room join butuh salah satunya.
  - `peer_offline` → REST offer (push terkirim dari PHP).
- UI call existing dipakai ulang (status dialing/ringing/active/ended sama).

## 5. Validasi (tiap unit punya bukti)

- `rebar3 eunit`: parse/aturan signaling murni + logika relay murni
  (pola ikuti `nusa_client_events_tests.erl`; jalur DB via smoke).
- `./gradlew :app:testDebugUnitTest`: `NvpPacketTest` (round-trip, vektor,
  10k paket nonce unik, tag rusak ditolak) + seleksi codec + jitter buffer.
- `tools/ws_smoke.py` diperluas: offer→answer→end WS, datagram UDP
  terautentikasi di-forward, echo balik, token salah di-drop,
  ukur pps/kbps ≤ budget profil ultra, dan token Erlang == token PHP
  (`/calls/nvp_join`) untuk call + user yang sama.
- Manual 1 HP: mode echo (dengar suara sendiri) = rantai penuh terbukti.
- Gerbang akhir: `assembleDebug` + `rebar3 dialyzer`.

## 6. Risiko → mitigasi (ditagih user, ditanam di desain)

1. Fragmentasi MediaCodec → probe runtime + fallback AMR-WB + codec
   terlapor di signaling/log/smoke (bukan asumsi).
2. Disiplin nonce GCM → unik-by-construction + uji 10k paket + tolak replay
   via seq (didokumentasikan di §3.2).
3. UDP publik internet → host/port konfiguratif; LAN dev jalan apa adanya;
   provision VPS di luar repo ini.
4. AEC/gain per HP → `VOICE_COMMUNICATION` + mode komunikasi + diagnostik
   engine di log; verifikasi perangkat wajib, bukan opsional.
5. Biaya bandwidth server → matematika §3.4 + counter relay + assert smoke.

## 7. Non-goal v1

Video, P2P langsung, E2EE kunci-penuh, grup >8, klien non-Android,
hapus path lama (keputusan: pertahankan paralel sebagai fallback).
