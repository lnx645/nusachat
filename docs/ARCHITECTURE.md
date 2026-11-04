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

Sejak migrasi, pengantar realtime utama adalah gateway Erlang/OTP
(`realtime/nusachat_realtime`, port 8091, detail di `realtime/README.md`):
supervisor OTP menaungi koneksi MySQL, registry socket per user (ETS),
poller outbox 350 ms, dan handler Cowboy — wire-compatible sehingga klien
(Android `WS_URL`) tidak berubah. Gateway PHP di 8090 tetap jalan sebagai
fallback. Database utama kini MySQL (`DB_DRIVER=mysql`); SQLite hanya arsip
di `/tmp/nusachat.sqlite.bak`.

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
| `message_edited` | Pesan diubah | `conversation_id`, `message` |
| `message_reaction` | Reaksi berubah | `conversation_id`, `message_id`, `action`, `reactions` |
| `conversation_pinned` | Pesan disematkan | `conversation_id`, `message_id`, `user_id` |
| `conversation_unpinned` | Sematan dilepas | `conversation_id`, `message_id`, `user_id` |
| `join_requested` | Permintaan gabung grup | `conversation_id`, `user` |
| `join_reviewed` | Hasil review gabung | `conversation_id`, `status` |
| `call_offer/answer/ice/end` | Signaling 1-1 (+`kind` di offer) | `call`, `sdp`/`candidate` |
| `call_room_offer/joined/left/ended/signal` | Signaling room grup | `call`, `user`/`sdp` |
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
- **Chat lanjutan**: long-press untuk balas/edit/teruskan/reaksi/sematkan/tarik, reply preview di composer, lampiran foto/video/audio/file, info grup (anggota, tambah/keluarkan, bisu, keluar, arsip).
- **Grup**: tautan undangan (`nusachat://group/<token>`, bisa diputar ulang), gabung tanpa berteman, antrean persetujuan admin, role owner/admin/member (admin keluarkan anggota biasa; owner kelola role).
- **Kontak**: sinkron kontak HP via `READ_CONTACTS` ke `/contacts/sync`, hapus teman dengan konfirmasi.
- **Momen**: composer multi-foto/video (maks 9, `VideoView` untuk mp4), hapus momen sendiri, pagination `before_id`, posting ulang (`reshared_from_id` + atribusi), bagikan ke chat (kirim ulang teks/media terenkripsi).
- **Story 24 jam**: tabel `stories` (`expires_at`), strip di Momen, `StoryViewer` (pager + hapus milik sendiri), event `story_new`, feed menyaring kedaluwarsa + blokir.
- **E2EE antar-perangkat**: `E2eeManager` (AES-GCM + RSA-2048 OAEP, kunci di `EncryptedSharedPreferences`), `device_keys` + `conversation_keys` (blob opaque per `device_id` + `key_fp` fingerprint SHA-256 pubkey), healing saat buka chat (`syncConversationKeys`: unwrap milikku → tetapkan bila kosong → bungkus untuk yang belum punya; reinstall = RSA baru dengan `device_id` sama wajib dibungkus ulang karena fingerprint beda), bungkus ulang saat kirim (send-heal) + saat event `device_added`/`keys_updated`, publikasi kunci saat login. Catatan: pesan lama yang blob-nya hanya untuk pubkey lama tak bisa dibuka di install baru sampai ada pemegang kunci kanonis yang buka chat/kirim (holding-side heal); terverifikasi realtime + plaintext dua arah via Erlang push (msg 701, conv 2).
- **Dompet**: `Wallet` controller + `Wallet_model` (topup, transfer transaksional + `wallet_txns`), `WalletScreen` (saldo, top up, kirim ke teman, riwayat), event `wallet_received`, flag `monetization: true`.
- **Profil**: gender, birthday (`YYYY-MM-DD`), discoverable, dan ganti kata sandi (sesi dicabut, wajib login ulang).
- **Auth ala LINE + Google OAuth**: sambutan (Login/Daftar) -> login Google atau username/sandi -> daftar bertahap (Google -> nama/username -> avatar -> sandi -> persetujuan). Client: Credential Manager (`GoogleAuthHelper`, audience `BuildConfig.GOOGLE_SERVER_CLIENT_ID`); server: `POST /auth/google` verifikasi RS256 via `Googleauth` (cert Google di-cache 12 jam, cek iss/aud/exp; `aud` harus di `GOOGLE_CLIENT_IDS`), `google_sub` tertaut -> login, baru -> 409 `google_register_required` -> client lengkapi profil -> 201 + avatar galeri diunggah pasca-daftar. Kolom `users.google_sub` (unik) + `google_email`. Smoke REST 139/139.
- **Upload**: `ActivityResultContracts.GetContent` → multipart ke `/uploads` → URL dikirim sebagai pesan/momen/avatar.
- **Push (FCM)**: `PushManager` init Firebase + channel Pesan/Panggilan, minta izin notifikasi (API 33+), ambil token dan `POST /push/register` setelah login; `NusaFirebaseService` render notifikasi data-only. Backend kirim best-effort via `Fcm`/legacy API, skip saat `FCM_SERVER_KEY` kosong atau user mute.
- **Voice call 1-1** (video call dihapus): `CallRepository` (`/calls/*`, selalu `kind` audio; `video` ditolak `403 video_disabled`) + `WebRtcCallManager` audio-only (`PeerConnectionFactory`, `JavaAudioDeviceModule` software AEC/NS, TURN Cloudflare via `/calls/ice_servers`) + `callState` di `AppViewModel`. Offer/answer/ICE lewat REST, event realtime `call_*` tiba via WS, FCM + `CallForegroundService` membangunkan callee (notif Jawab/Tolak/Tutup, jalan di background). UI: tombol telepon di chat langsung, `VoiceCallScreen` (avatar + timer), layar penuh panggilan masuk, toggle mic/speaker via `AudioManager`.
- **Panggilan grup (mesh audio)**: room di tabel `calls` (`conversation_id` terisi, `callee_id = 0`) + `call_participants`. `GroupCallMesh` memegang satu `WebRtcCallManager` per lawan bicara; SDP via `/calls/room_signal` (`call_room_signal`), ICE via `/calls/ice` + `to_user_id`. UI: `GroupCallScreen` (dering/aktif, daftar partisipan, keluar), entry dari info grup, tolak tanpa jejak via `declineGroupCall`.
- **UI pattern**: tidak ada `AlertDialog` tersisa — konfirmasi pakai `NusaConfirmSheet`, aksi pakai `NusaActionSheet` (bottom sheet), info grup/chat baru/komposer momen/ganti sandi pakai layar penuh atau bottom sheet.
- **Latar Chat**: halaman khusus ala WA/Telegram/LINE (`ChatThemeScreen` dari tab Saya → Umum). Wallpaper bawaan: doodle Nusa terang (`chat_doodle_light.webp`, default: putih semu abu #ECE5DD) + doodle gelap (`chat_doodle_dark.webp`), 6 warna solid, gambar galeri (`OpenDocument` + izin persistable + scrim 28%). Setiap pilihan tersimpan di `ChatThemeStore` dan selalu berpasangan dengan bubble (Otomatis/Nusa/WA/Biru/Ungu/Gelap: mine+other+warna teks) agar kontras terjaga; jam + centang di atas wallpaper ikut terang/gelap latar.
- **Orang Terdekat realtime via Erlang** (beban PHP ~nol): layar kirim `location.update` lewat WS ke `nusa_nearby` (gen_server + ETS, persist 1 UPDATE MySQL, validasi koordinat server-side); daftar awal `nearby.result` dari query geo Erlang (mirror `User_model::nearby`: aktif+discoverable+segar 7 hari, kecuali blokir, filter haversine, `friend_status` + jarak); perubahan berikutnya di-push `nearby.changed` ke subscriber yang radiusnya mencakup. Client: `startNearbyLive`/`stopNearbyLive`, badge "Live • realtime", fallback REST bila socket putus; handler WS tahan crash callee (jawab `nearby.error`, koneksi tetap hidup). Smoke WS 18/18 termasuk regresi multi-kandidat.

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
- E2EE demo (AES-GCM + distribusi kunci RSA, tanpa ratchet/forward secrecy); pesan non-teks dan metadata tidak terenkripsi.
- Panggilan grup mesh audio (tanpa SFU); kualitas menurun di atas ~6 partisipan dan butuh UDP/STUN terbuka.
- Dompet hanya koin demo; bukan payment gateway dan tanpa audit trail eksternal.
