# Arsitektur NusaChat

Dokumen ini menjelaskan bagaimana backend, gateway realtime, database, dan klien Android bekerja bersama.

## Gambaran besar

```
┌────────────────────┐        HTTPS/JSON         ┌──────────────────────┐
│  Android (Compose) │ ────────────────────────> │  PHP API (CI3)       │
│  OkHttp + Coil     │ <──────────────────────── │  PHP-FPM / php -S    │
└─────────┬──────────┘                           └───────┬──────────────┘
          │                                              │ PDO
          │ WSS (JSON frames)                            ▼
          │                                      ┌──────────────────────┐
          │                                      │ SQLite / MySQL       │
          │                                      │ users, messages, ... │
          │                                      │ realtime_events      │
          │                                      └───────┬──────────────┘
          │                                              │ polling 350 ms
          ▼                                              ▼
┌───────────────────────────────────────────────────────────────────────┐
│  PHP CLI WebSocket gateway (`tools/ws-server.php`)                    │
│  stream_socket_server + stream_select, fan-out per user_id            │
└───────────────────────────────────────────────────────────────────────┘
```

Ada dua proses PHP yang terpisah:

1. **API** melayani request HTTP. Di produksi dijalankan lewat PHP-FPM + nginx; di lokal memakai `php -S`.
2. **Gateway WebSocket** adalah daemon CLI yang memegang koneksi panjang. Proses ini tidak ikut melayani request HTTP, sehingga tidak memblokir API.

Keduanya berkomunikasi lewat database, bukan lewat panggilan langsung. API menulis event ke `realtime_events`, gateway membaca tabel itu dan mengirim frame ke socket milik user yang sesuai.

## Alur request API

1. `index.php` memuat `.env`, mendefinisikan path, lalu menyerahkan kontrol ke CodeIgniter.
2. `MY_Controller` memuat konfigurasi `app`, library `Store`, dan helper API.
3. Body JSON dibaca dari `php://input`; form multipart tetap ditangani PHP secara native untuk upload.
4. `require_auth()` memvalidasi bearer JWT, memeriksa `typ=access`, masa berlaku, user aktif, dan `token_version`.
5. Controller memanggil model; model memakai PDO wrapper `Store` dengan prepared statement.
6. Response selalu memakai envelope JSON:
   - sukses: `{"ok":true,"data":...}`
   - gagal: `{"ok":false,"error":{"code":"...","message":"...","details":{...}}}`

`MY_Controller::json()`, `ok()`, dan `fail()` bertipe `never` karena selalu berakhir dengan `exit`.

## Autentikasi

- Password disimpan dengan `password_hash()` (bcrypt/argon sesuai default PHP).
- Access token: JWT HS256 berisi `sub`, `username`, `tv` (token version), `typ=access`, `iat`, `exp`, `jti`.
- Refresh token: string opaque `jti.secret`. Yang disimpan di database hanya `sha256(secret)`.
- Saat refresh, token lama langsung dicabut (rotasi). Refresh token yang sudah dipakai tidak bisa dipakai ulang.
- Logout mencabut semua refresh token user dan menaikkan `users.token_version`, sehingga access token lama langsung invalid.
- Login dan register dibatasi rate limit per IP/akun; endpoint lain memakai rate limit per user.

## Realtime

### Gateway

`tools/ws-server.php`:

- Listen di `WS_BIND` (default `0.0.0.0:8090`).
- Handshake RFC 6455 manual, validasi `Sec-WebSocket-Key`.
- Token diambil dari query `?token=<access_token>`; token diverifikasi dengan secret yang sama dan `token_version` dicek ke database.
- `stream_set_blocking(false)` + `stream_select()` dengan timeout 400 ms; koneksi idle tidak memblokir koneksi lain.
- Menangani frame text, continuation, ping, pong, dan close.
- Mengirim ping server tiap 25 detik.
- Polling `realtime_events` tiap 350 ms dengan cursor `id`.
- Menghapus event lebih lama dari 24 jam setiap 5 menit.

### Event yang dikirim

Envelope:

```json
{
  "event_id": 42,
  "type": "message",
  "data": { "...": "..." },
  "ts": "2026-11-03T10:00:00Z"
}
```

Tipe event:

| Tipe | Kapan | Data utama |
| --- | --- | --- |
| `connected` | Setelah handshake | `user_id`, `server_time` |
| `pong` | Balasan ping aplikasi | `server_time` |
| `message` | Pesan baru | `conversation_id`, `message` |
| `read` | Lawan membaca pesan | `conversation_id`, `user_id`, `message_id` |
| `typing` | Lawan mengetik | `conversation_id`, `user_id`, `display_name` |
| `message_revoked` | Pesan ditarik | `conversation_id`, `message_id` |
| `friend_request` | Permintaan pertemanan masuk | `request_id`, `user` |
| `friend_accepted` | Permintaan diterima | `user` |
| `conversation_updated` | Anggota grup berubah | `conversation_id`, `removed_user_id` |
| `moment_new` | Teman membagikan momen | `moment_id`, `author` |
| `moment_liked` | Momen disukai | `moment_id`, `user` |
| `moment_comment` | Komentar baru | `moment_id`, `comment_id`, `user`, `body` |

### Fallback mobile

Socket bisa putus (background, jaringan berubah). Karena itu klien tetap melakukan:

- Polling pesan chat tiap 7 detik saat layar chat terbuka.
- Sinkronisasi percakapan/teman/permintaan tiap 60 detik.
- Reconnect WebSocket dengan exponential backoff 1–30 detik.

Pendekatan ini membuat aplikasi tetap berfungsi walau realtime sempat mati.

## Database

| Tabel | Fungsi |
| --- | --- |
| `users` | Profil, lokasi kasar, `token_version`, status aktif |
| `tokens` | Refresh token (hash), perangkat, masa berlaku, revokasi |
| `contacts` | Hash nomor telepon hasil sinkronisasi kontak |
| `friendships` | Permintaan dan status pertemanan |
| `conversations` | Direct/group, `direct_key` unik untuk chat berdua |
| `conversation_members` | Anggota, role, `last_read_message_id`, mute |
| `messages` | Pesan, lampiran, `client_id` idempoten, status, revokasi |
| `moments` | Momen dan `media_json` |
| `moment_likes` | Suka unik per user/momen |
| `moment_comments` | Komentar momen |
| `realtime_events` | Outbox event untuk gateway |
| `rate_limits` | Fixed-window rate limiter |

Waktu disimpan sebagai string ISO-8601 UTC agar konsisten antara SQLite dan MySQL. SQLite memakai WAL, `busy_timeout=10000`, dan gateway WS mencoba ulang operasi tulis singkat saat database terkunci; MySQL memakai `utf8mb4` dan `InnoDB`.

## Mobile

- **Manual DI**: `AppViewModel` membuat `SessionStore`, `ApiClient`, `ChatRepository`, `LocationHelper`, dan `ChatSocket`.
- **State**: `MutableStateFlow` untuk sesi, percakapan, chat, teman, permintaan, momen, nearby, komentar, dan status realtime.
- **Networking**: OkHttp + `JSONObject`/`JSONArray`; tidak ada Retrofit/Room/KSP.
- **Session**: access token, refresh token, dan snapshot user di SharedPreferences privat.
- **UI**: Compose Material 3 dengan 4 tab bawah. Halaman chat, momen, nearby, dan edit profil adalah overlay yang menyembunyikan bottom bar.
- **Optimistic send**: pesan langsung muncul dengan `client_id`; saat respons server datang, pesan sementara diganti pesan asli. Event realtime dengan `client_id` yang sama juga direkonsiliasi. Pesan gagal bisa dikirim ulang dari menu long-press.
- **Chat lanjutan**: long-press untuk balas/tarik, reply preview di composer, lampiran foto/video/audio/file, info grup (anggota, tambah/keluarkan, bisu, keluar).
- **Kontak**: sinkron kontak HP via `READ_CONTACTS` ke `/contacts/sync`, hapus teman dengan konfirmasi.
- **Momen**: composer multi-foto (`GetMultipleContents`, maks 9), hapus momen sendiri, pagination `before_id`.
- **Profil**: gender, birthday (`YYYY-MM-DD`), discoverable, dan ganti kata sandi (sesi dicabut, wajib login ulang).
- **Upload**: `ActivityResultContracts.GetContent` → multipart ke `/uploads` → URL dikirim sebagai pesan/momen/avatar.
- **Push (FCM)**: `PushManager` init Firebase + channel Pesan/Panggilan, minta izin notifikasi (API 33+), ambil token dan `POST /push/register` setelah login; `NusaFirebaseService` render notifikasi data-only. Backend kirim best-effort via `Fcm`/legacy API, skip saat `FCM_SERVER_KEY` kosong atau user mute.
- **Voice call (WebRTC audio-only)**: `CallRepository` (`/calls/*`) + `WebRtcCallManager` (`PeerConnectionFactory`, `JavaAudioDeviceModule`, STUN Google) + `callState` di `AppViewModel`. Offer/answer/ICE lewat REST, event realtime `call_*` tiba via WS, FCM membangunkan callee. UI: tombol telepon di chat langsung, `VoiceCallScreen`, layar penuh panggilan masuk, toggle mic/speaker via `AudioManager`.
- **UI pattern**: tidak ada `AlertDialog` tersisa — konfirmasi pakai `NusaConfirmSheet`, aksi pakai `NusaActionSheet` (bottom sheet), info grup/chat baru/komposer momen/ganti sandi pakai layar penuh atau bottom sheet.

## Deployment produksi

Rekomendasi minimum:

1. PHP 8.2+ dengan OPcache, `pdo_mysql`, `mbstring`, `fileinfo`.
2. MySQL 8 (atau MariaDB setara), bukan SQLite.
3. nginx + PHP-FPM untuk API; `APP_URL` dan `CORS_ORIGINS` diset eksplisit. Saat pengembangan LAN, `APP_URL` boleh tetap `localhost` karena URL upload akan mengikuti host request; jangan lupa membuka port API dan WebSocket di firewall.
4. Gateway WS di belakang reverse proxy yang mendukung upgrade (`wss://`), dijalankan lewat systemd/supervisord agar otomatis restart.
5. `APP_SECRET` acak minimal 32 byte; jangan memakai nilai contoh.
6. Backup database dan folder `storage/uploads`.

Untuk skala lebih besar, ganti polling `realtime_events` dengan Redis pub/sub atau pindahkan gateway ke Workerman/Swoole/Node.js. Kontrak event JSON bisa dipertahankan sehingga klien tidak perlu diubah.

## Batasan yang diketahui

- Gateway WS single-process; batas praktis `stream_select()` sekitar 1024 file descriptor.
- Handshake membaca header secara blocking dengan timeout 3 detik; client baru yang lambat bisa menahan loop sebentar.
- Belum ada enkripsi end-to-end; pesan terenkripsi saat transit (HTTPS/WSS) dan tersimpan di database server.
- Belum ada panggilan suara/video, notifikasi push, atau monetisasi.
- Build Android belum diverifikasi compiler pada lingkungan pengembangan ini.
