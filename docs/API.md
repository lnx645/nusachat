# API NusaChat v1

Base URL lokal: `http://127.0.0.1:8080/api/v1`

Semua response JSON memakai envelope yang sama:

```json
{ "ok": true, "data": { } }
```

```json
{
  "ok": false,
  "error": {
    "code": "validation_error",
    "message": "Missing required field(s): password",
    "details": { "fields": ["password"] }
  }
}
```

Endpoint yang membutuhkan login memakai header:

```
Authorization: Bearer <access_token>
```

`Content-Type: application/json` untuk request JSON, atau `multipart/form-data` untuk upload.

## Autentikasi

### POST `/auth/register`

Membuat akun baru dan langsung mengembalikan token.

```json
{
  "username": "rina",
  "password": "rahasia123",
  "display_name": "Rina",
  "phone": "081234567890",
  "device": "NusaChat Android"
}
```

Aturan: username 4–20 karakter (`a-z`, `0-9`, `.`, `_`), password 6–72 karakter, display name 2–40 karakter. `phone` opsional dan dinormalisasi ke format internasional.

Response `201`:

```json
{
  "ok": true,
  "data": {
    "user": { "id": 5, "username": "rina", "display_name": "Rina" },
    "auth": {
      "access_token": "<jwt>",
      "refresh_token": "<jti.secret>",
      "token_type": "Bearer",
      "expires_in": 604800,
      "refresh_expires_in": 2592000
    }
  }
}
```

### POST `/auth/login`

```json
{ "login": "nusa", "password": "password123", "device": "NusaChat Android" }
```

`login` boleh berisi username atau nomor HP. Response sama dengan register.

### POST `/auth/refresh`

```json
{ "refresh_token": "<jti.secret>" }
```

Refresh token lama dicabut dan diganti pasangan baru. Response sama dengan register.

### POST `/auth/logout` 🔒

Body opsional `{ "refresh_token": "..." }`. Semua sesi user dicabut dan `token_version` dinaikkan.

### GET `/auth/check_username?username=rina`

```json
{ "ok": true, "data": { "username": "rina", "available": true, "reason": null } }
```

### POST `/auth/forgot`

`{ "login": "rina" }` — selalu `200 { sent: true }` (anti enumerasi). Di development response juga berisi `reset_token` (15 menit) karena belum ada gateway SMS/email; di produksi token hanya dicatat di log.

### POST `/auth/reset`

`{ "token": "...", "new_password": "..." }` — token sekali pakai; semua sesi dicabut.

### GET `/auth/sessions` 🔒

Daftar sesi aktif (`jti`, `device`, `created_at`, `last_used_at`, `expires_at`).

### POST `/auth/revoke_session` 🔒

`{ "jti": "..." }` — cabut satu sesi.

## Konfigurasi

### GET `/config`

Info aplikasi, fitur, batas, dan `ws_url`.

### GET `/config/ping`

```json
{ "ok": true, "data": { "pong": true, "db": "sqlite", "server_time": "...", "version": "0.2.0" } }
```

## Pengguna 🔒

| Method | Path | Keterangan |
| --- | --- | --- |
| GET | `/users/me` | Profil sendiri |
| POST/PATCH | `/users/update` | Update profil |
| GET | `/users/search?q=rina` | Cari pengguna (min. 2 karakter) |
| GET | `/users/show?id=5` | Profil pengguna lain |
| POST | `/users/password` | Ganti password |
| POST/DELETE | `/users/delete` | `password` → nonaktifkan + anonimkan + cabut relasi (username dipertahankan) |
| POST | `/users/presence` | Kirim heartbeat/lokasi |
| POST | `/users/device_key` | `device_id`, `public_key` (base64 kunci publik RSA perangkat) |
| GET | `/users/device_keys?user_id=5` | — (daftar kunci publik perangkat user) |

Field `users/update`: `username` (unik, aturan register), `display_name`, `bio`, `gender` (`male`/`female`/`other`), `birthday` (`YYYY-MM-DD`), `avatar_url`, `discoverable`, `phone`.

## Laporan 🔒

| Method | Path | Body |
| --- | --- | --- |
| POST | `/reports/create` | `target_type` (`user`/`message`/`moment`/`comment`/`story`), `target_id`, `reason` (`spam`/`abuse`/`fraud`/`porn`/`violence`/`other`), `details?` |
| GET | `/reports/mine` | — |

```json
{ "lat": -6.2, "lng": 106.816 }
```

`users/presence` tanpa `lat`/`lng` hanya memperbarui `last_seen_at`.

## Teman 🔒

| Method | Path | Body/Query |
| --- | --- | --- |
| GET | `/friends` | — |
| GET | `/friends/requests` | — |
| POST | `/friends/send` | `user_id`, `message?` sapaan ≤160 (ditolak bila salah satu memblokir) |
| POST | `/friends/remark` | `user_id`, `remark?` (kosong = hapus; maks 40, khusus teman) |
| POST | `/friends/respond` | `request_id`, `action` = `accept`/`decline` |
| POST/DELETE | `/friends/remove` | `user_id` |
| POST | `/friends/block` | `user_id` |
| POST/DELETE | `/friends/unblock` | `user_id` |
| GET | `/friends/blocked` | — (daftar yang saya blokir) |
| GET | `/friends/status?user_id=5` | — |

Blokir satu arah: chat/panggilan langsung, permintaan teman, pencarian, profil, dan nearby terhadap yang diblokir ditolak. Bila saya memblokir → `403 blocked`; bila saya diblokir → `404` seolah tidak ada. Grup tidak terpengaruh.

`GET /friends/requests` mengembalikan `incoming` dan `outgoing` (termasuk `greeting`). Daftar teman menyertakan `remark` (nama panggilan pribadi).

## Kontak 🔒

### POST `/contacts/sync`

```json
{
  "contacts": [
    { "name": "Sari", "phone": "08123456789" },
    { "name": "Budi", "phone": "+628123456780" }
  ]
}
```

Maksimal 2000 kontak per request. Nomor di-hash dengan HMAC; server tidak menyimpan nomor mentah dari buku kontak. Response berisi `received`, `matched`, dan `users`.

## Percakapan 🔒

| Method | Path | Body/Query |
| --- | --- | --- |
| GET | `/conversations` | — |
| GET | `/conversations/show?id=1` | — |
| POST | `/conversations/create_direct` | `user_id` |
| POST | `/conversations/create_group` | `title`, `member_ids` |
| GET | `/conversations/messages` | `id`, `before_id?`, `after_id?`, `limit?` |
| POST | `/conversations/send` | lihat di bawah |
| POST | `/conversations/forward` | `message_id`, `to_id`, `body?`, `client_id?` |
| GET | `/conversations/search` | `id`, `q` (min. 2 huruf), `limit?` |
| POST | `/conversations/pin` | `id`, `message_id` |
| POST | `/conversations/unpin` | `id`, `message_id` |
| GET | `/conversations/pins` | `id` |
| POST | `/conversations/archive` | `id`, `archived?` (default true) |
| GET | `/conversations/keys` | `id` → `my_key` (milikku) + `holders` (yang sudah punya) |
| POST | `/conversations/set_keys` | `id`, `keys: [{user_id, wrapped_key}]` (khusus anggota) |
| POST | `/conversations/read` | `id`, `message_id` |
| POST | `/conversations/typing` | `id` |
| POST | `/conversations/add_members` | `id`, `user_ids` |
| POST | `/conversations/remove_member` | `id`, `user_id` |
| POST | `/conversations/set_role` | `id`, `user_id`, `role` = `admin`/`member` (owner saja) |
| POST | `/conversations/invite` | `id`, `rotate?` → `invite_token` + `invite_link` |
| POST | `/conversations/join` | `token` (token mentah atau tautan penuh) |
| GET | `/conversations/join_requests` | `id` (admin saja) |
| POST | `/conversations/review_request` | `request_id`, `action` = `approve`/`reject` (admin saja) |
| POST | `/conversations/mute` | `id`, `muted` |
| POST | `/conversations/pin_chat` | `id`, `pinned?` (default true) |
| POST | `/conversations/mark_unread` | `id`, `message_id?` |
| GET | `/conversations/search_all` | `q` (min. 2 huruf), `limit?` — cari di semua chat |
| POST | `/conversations/transfer_owner` | `id`, `user_id` (owner grup saja) |

`GET /conversations` mendukung `?archived=1` untuk daftar arsip (default disembunyikan) serta pagination `?limit=` + `?before=` (kursor waktu); urutan: disematkan dulu, lalu pesan terakhir.

Chat langsung hanya bisa dibuat dengan teman. Grup maksimal 50 anggota dan hanya owner yang bisa menambah anggota.

Undangan: anggota bisa meminta tautan via `/conversations/invite` saat `invite_link_enabled` aktif; tautan bisa diputar ulang dengan `rotate: true`. Gabung via `/conversations/join` tanpa perlu berteman dulu. Bila `approve_new_members` aktif, gabung menjadi permintaan `pending` (HTTP 202) yang disetujui/ditolak admin; admin diberi event `join_requested`, pemohon diberi `join_reviewed`. Role: owner mengangkat/mencabut admin via `/conversations/set_role`; admin bisa mengeluarkan anggota biasa, hanya owner yang bisa mengeluarkan admin.

### Kirim pesan

```json
{
  "id": 1,
  "type": "text",
  "body": "Halo!",
  "client_id": "7a8f0e6e-5d1e-4f0e-9d7c-2a4b6c8d0e1f"
}
```

Field tambahan: `reply_to_id`, `attachment_url`, `attachment_meta`. Untuk `type` selain `text`/`sticker`, `attachment_url` wajib. Tipe yang didukung: `text`, `image`, `file`, `audio`, `video`, `location`, `sticker`, `redpacket` (redpacket hanya dibuat server via dompet).

Mention: tulis `@username` anggota percakapan di isi pesan teks; server menyimpannya sebagai `mentions: [user_id, ...]` dan `mentioned_me: true` untuk user yang disebut. `@semua`/`@all` menyebut semua anggota.

`client_id` membuat pengiriman idempoten: request ulang dengan `client_id` yang sama mengembalikan pesan lama dengan `duplicate: true` dan HTTP `200`.

### Ambil pesan

```
GET /conversations/messages?id=1&limit=40
GET /conversations/messages?id=1&before_id=120&limit=30
GET /conversations/messages?id=1&after_id=200&limit=50
```

Response:

```json
{
  "ok": true,
  "data": {
    "messages": [],
    "has_more": false,
    "conversation_id": 1
  }
}
```

`before_id` untuk memuat pesan lama; `after_id` untuk polling pesan baru. Limit maksimal 100.

### Tarik pesan

`POST /messages/revoke` dengan `{ "message_id": 123 }`. Hanya pengirim, maksimal 24 jam setelah dikirim.

### Edit pesan

`POST /messages/edit` dengan `{ "message_id": 123, "body": "teks baru" }`. Hanya pengirim, maksimal 24 jam setelah dikirim. Response berisi `message` lengkap dengan `edited_at`.

### Reaksi

`POST /messages/react` dengan `{ "message_id": 123, "emoji": "👍" }`. Satu reaksi per user per pesan; kirim emoji yang sama untuk mencabut, emoji lain untuk mengganti. Response: `{ "action": "added"|"removed"|"replaced", "reactions": [{ "emoji", "count", "reacted_by_me" }] }`.

### Teruskan pesan

`POST /conversations/forward` dengan `{ "message_id": 123, "to_id": 5 }`. Menyalin pesan ke percakapan tujuan dengan `forwarded: true` dan `forwarded_from_id` terisi. `reply_to` tidak ikut disalin. Klien E2EE boleh menyertakan `body` berisi isi yang sudah dienkripsi ulang dengan kunci percakapan tujuan.

### Bintang & hapus-untuk-saya

| Method | Path | Body |
| --- | --- | --- |
| POST | `/messages/star` | `message_id` |
| POST | `/messages/unstar` | `message_id` |
| GET | `/messages/starred` | `limit?` |
| POST | `/messages/hide` | `message_id` (sembunyikan hanya untukku) |

## Momen 🔒

| Method | Path | Body/Query |
| --- | --- | --- |
| GET | `/moments` | `before_id?`, `limit?` |
| GET | `/moments/show?id=1` | — |
| POST | `/moments/create` | `body?`, `media?` (foto/video, maks 9), `audience?` (`friends`/`private`) |
| POST | `/moments/edit` | `id`, `body` (milik sendiri) → `edited_at` |
| POST/DELETE | `/moments/delete` | `id` |
| POST | `/moments/reshare` | `id` → salinan + atribusi `reshared: {moment_id, author}` |
| GET | `/moments/likes` | `id` → daftar penyuka |
| GET | `/moments/stories` | — (story aktif teman + sendiri + `view_count`) |
| POST | `/moments/story_create` | `body?`, `media?` (maks 4), `expires_in_hours?` (default 24) |
| POST/DELETE | `/moments/story_delete` | `id` |
| POST | `/moments/story_view` | `id` (catat tontonan) |
| GET | `/moments/story_views` | `id` (khusus pembuat) |
| POST | `/moments/like` | `id` |
| POST/DELETE | `/moments/unlike` | `id` |
| GET | `/moments/comments?id=1` | — |
| POST | `/moments/comment` | `id`, `body` |
| POST/DELETE | `/moments/comment_delete` | `id` (pemilik komentar/pemilik momen) |

`media` adalah array URL hasil upload, maksimal 9. Momen minimal berisi teks atau satu media. Feed berisi momen sendiri dan teman; momen `private` hanya terlihat pemiliknya.

## Orang terdekat 🔒

```
GET /nearby?lat=-6.2&lng=106.816&radius=25
```

`radius` dibatasi 1–100 km. Response berisi `users` dengan `distance_km`, `radius_km`, dan `location` yang dibulatkan. Lokasi disimpan kasar (3 desimal, sekitar 110 m).

## Upload 🔒

### POST `/uploads`

Multipart form dengan field `file`.

Tipe yang diterima: JPEG, PNG, WebP, GIF, MP4, M4A, MP3, OGG, PDF. Maksimal 25 MB (bisa diubah lewat `UPLOAD_MAX_MB`). Rate limit 60 upload/jam.

`url` memakai `APP_URL` bila diisi host publik. Jika `APP_URL` kosong atau masih `localhost`/`127.0.0.1`, URL dibentuk dari host request, sehingga otomatis bisa dibuka dari HP yang mengakses API lewat IP LAN.

```json
{
  "ok": true,
  "data": {
    "url": "http://localhost:8080/uploads/2026/11/abc.png",
    "path": "uploads/2026/11/abc.png",
    "meta": { "name": "foto.png", "size": 12345, "mime": "image/png", "width": 800, "height": 600 }
  }
}
```

## Sinkronisasi 🔒

```
GET /sync?since=2026-11-01T00:00:00Z
```

Ringkasan untuk badge dan reconnect:

```json
{
  "ok": true,
  "data": {
    "server_time": "2026-11-03T10:00:00Z",
    "unread_total": 3,
    "conversation_count": 5,
    "pending_friend_requests": 1,
    "friend_count": 4,
    "new_moments_since": 2,
    "ws_url": "ws://localhost:8091"
  }
}
```

## Push 🔒

| Method | Path | Body |
| --- | --- | --- |
| POST | `/push/register` | `token`, `platform?` |
| POST/DELETE | `/push/unregister` | `token?` |
| GET | `/push/status` | — |

Butuh `FCM_SERVER_KEY` di backend. Tanpa key, response `push_enabled: false` dan API tetap jalan; notifikasi hanya dari WS saat app terbuka. Payload FCM data-only (`event`, `title`, `body` + id terkait); channel Android: `nusachat_messages` dan `nusachat_calls`.

## Voice & video call 🔒

| Method | Path | Body |
| --- | --- | --- |
| POST | `/calls/offer` | `to_user_id`, `sdp`, `kind?` (`audio`/`video`) |
| POST | `/calls/answer` | `call_id`, `sdp` |
| POST | `/calls/ice` | `call_id`, `candidate` (`{candidate, sdpMid, sdpMLineIndex}`), `to_user_id?` (wajib untuk room grup) |
| POST | `/calls/reject` | `call_id` |
| POST | `/calls/end` | `call_id` |
| GET | `/calls/incoming` | — (mengembalikan `calls` + `rooms`) |
| GET | `/calls/history` | `limit?`, `before_id?` → riwayat + `direction`, `missed`, `duration` |
| GET | `/calls/ice_servers` | — (kredensial TURN Cloudflare short-lived per `docs/TURN.md`, fallback STUN) |

Signaling lewat outbox realtime (`call_offer`, `call_answer`, `call_ice`, `call_end`) + FCM wake-up untuk `call_offer`. Media peer-to-peer WebRTC (TURN Cloudflare via `/calls/ice_servers`, fallback STUN `stun:stun.cloudflare.com:3478`). Status: `ringing` → `active` → `ended`/`rejected`. Grup tetap mesh audio.

### Panggilan grup 🔒

| Method | Path | Body |
| --- | --- | --- |
| POST | `/calls/room_create` | `conversation_id`, `kind?` → room + `participants`, `reused` |
| POST | `/calls/room_join` | `call_id` → room + `participants` |
| POST | `/calls/room_leave` | `call_id` → `{left, ended}` (terakhir keluar menutup room) |
| GET | `/calls/room_state` | `call_id` → room + `participants` |
| POST | `/calls/room_signal` | `call_id`, `to_user_id`, `sdp`, `signal` (`offer`/`answer`) |

Room adalah mesh WebRTC audio: tiap anggota yang gabung bertukar SDP dengan anggota yang sudah ada via `room_signal` (`call_room_signal`) dan ICE via `/calls/ice` + `to_user_id`. Event: `call_room_offer`, `call_room_joined`, `call_room_left`, `call_room_ended`, `call_room_signal`.

## E2EE 🔒

Pesan teks dienkripsi AES-256 GCM di perangkat sebelum dikirim; server hanya menyimpan ciphertext (base64). Kunci sesi dibuat per percakapan dan didistribusikan antar perangkat/anggota lewat RSA-2048 OAEP:

1. Perangkat mengunggah kunci publik via `POST /users/device_key`.
2. Klien membaca `GET /conversations/keys`: bila `my_key` ada → buka dengan kunci privat; bila belum ada kunci sama sekali → tetapkan kunci lokal untuk semua anggota; bila memegang kunci kanonis → bungkus untuk anggota yang belum punya via `POST /conversations/set_keys`.
3. Server menyimpan blob opaque dan tidak pernah melihat kunci.

Bukan double-ratchet produksi: tanpa forward secrecy dan tanpa rotasi otomatis.

## Dompet 🔒

Koin demo (bilangan bulat, bukan uang sungguhan / bukan payment gateway).

| Method | Path | Body |
| --- | --- | --- |
| GET | `/wallet` | — → `{balance, has_pin}` |
| POST | `/wallet/topup` | `amount` (1–10.000.000) |
| POST | `/wallet/transfer` | `to_user_id`, `amount`, `note?`, `pin?` (atomik, saldo kurang → 422) |
| GET | `/wallet/history` | `limit?` → `{balance, transactions}` |
| POST | `/wallet/set_pin` | `pin` (4–6 digit), `old_pin?` (wajib bila sudah ada PIN) |
| POST | `/wallet/check_pin` | `pin` |
| POST | `/wallet/request_money` | `to_user_id`, `amount`, `note?` |
| GET | `/wallet/requests` | — → `{incoming, outgoing}` |
| POST | `/wallet/request_pay` | `request_id`, `pin?` |
| POST | `/wallet/request_decline` | `request_id` |
| POST | `/wallet/request_cancel` | `request_id` |
| POST | `/wallet/redpacket_create` | `conversation_id`, `total`, `count`, `note?`, `pin?`, `client_id?` |
| GET | `/wallet/redpacket` | `id` (anggota chat saja) |
| POST | `/wallet/redpacket_claim` | `packet_id` |

Transfer ke sesama user aktif; penerima diberi event realtime `wallet_received`. Bila pengirim memasang PIN, `transfer`/`request_pay`/`redpacket_create` wajib `pin` yang benar (`403 pin_required` bila salah).

Amplop merah: debit pengirim atomik + pesan `type=redpacket` berisi `packet_id` di `attachment_meta`. Klaim acak ala luck-draw (min 1 koin, total pas); pengirim tidak bisa klaim; kedaluwarsa 24 jam dengan pengembalian sisa otomatis (lazy). Event: `redpacket_claimed`. Tagihan: event `wallet_requested` (+ push), `wallet_request_paid`, `wallet_request_declined`.

## DTO

### User

```json
{
  "id": 1,
  "uuid": "…",
  "username": "nusa",
  "display_name": "Nusa Pratama",
  "avatar_url": null,
  "bio": "Halo!",
  "gender": "male",
  "online": true,
  "last_seen_at": "2026-11-03T10:00:00Z"
}
```

Untuk profil sendiri ditambah `phone`, `birthday`, `discoverable`, `created_at`. Pencarian dan nearby juga menambahkan `friend_status`, `request_id`, `request_direction`, dan `distance_km` bila relevan.

### Message

```json
{
  "id": 12,
  "uuid": "…",
  "conversation_id": 3,
  "sender": { "id": 1, "display_name": "Nusa", "username": "nusa", "avatar_url": null },
  "type": "text",
  "body": "Halo",
  "attachment_url": null,
  "attachment_meta": null,
  "reply_to_id": null,
  "forwarded": false,
  "forwarded_from_id": null,
  "mentions": [],
  "mentioned_me": false,
  "reactions": [{ "emoji": "👍", "count": 2, "reacted_by_me": true }],
  "client_id": "…",
  "status": "read",
  "revoked": false,
  "edited_at": null,
  "created_at": "2026-11-03T10:00:00Z"
}
```

`status`: `sent`, `delivered`, atau `read`. Pesan yang ditarik memiliki `revoked: true`, `body: null`, dan `attachment_url: null`.

### Conversation

```json
{
  "id": 3,
  "uuid": "…",
  "type": "direct",
  "title": "Sari",
  "avatar_url": null,
  "created_by": 1,
  "last_message": { },
  "last_message_at": "2026-11-03T10:00:00Z",
  "unread_count": 2,
  "role": "member",
  "members": [],
  "created_at": "2026-11-01T10:00:00Z",
  "peer": { }
}
```

Chat direct menambahkan `peer`. Grup menambahkan `member_count`; `members` diisi pada respons `show` dan `create_*`.

### Moment

```json
{
  "id": 7,
  "uuid": "…",
  "author": { "id": 2, "display_name": "Sari", "username": "sari", "avatar_url": null },
  "body": "Senja hari ini",
  "media": ["http://localhost:8080/uploads/2026/11/foto.jpg"],
  "like_count": 3,
  "comment_count": 1,
  "liked_by_me": false,
  "created_at": "2026-11-03T09:00:00Z"
}
```

## WebSocket

URL: `ws://<host>:8091/?token=<access_token>`

Handshake gagal dengan HTTP `401` bila token tidak valid, kedaluwarsa, atau `token_version` tidak cocok.

Setelah terhubung, server mengirim:

```json
{
  "type": "connected",
  "data": { "user_id": 1, "server_time": "2026-11-03T10:00:00Z" }
}
```

Event berikutnya memakai envelope:

```json
{
  "event_id": 42,
  "type": "message",
  "data": { "conversation_id": 3, "message": { } },
  "ts": "2026-11-03T10:00:00Z"
}
```

Ping aplikasi:

```json
{ "type": "ping" }
```

Balasan:

```json
{ "type": "pong", "data": { "server_time": "2026-11-03T10:00:00Z" } }
```

Server juga mengirim ping frame WebSocket setiap 25 detik. Klien harus membalas pong (OkHttp melakukannya otomatis).

Tipe event: `message`, `read`, `typing`, `message_revoked`, `message_edited`, `message_reaction`, `conversation_pinned`, `conversation_unpinned`, `friend_request`, `friend_accepted`, `conversation_updated`, `moment_new`, `moment_liked`, `moment_comment`, `story_new`.

## Status HTTP dan kode error

| HTTP | Kode umum | Arti |
| --- | --- | --- |
| 200 | — | Sukses |
| 201 | — | Resource dibuat |
| 400 | `bad_request` | Request tidak valid |
| 401 | `unauthorized`, `token_expired`, `token_revoked`, `invalid_credentials` | Auth gagal |
| 403 | `forbidden`, `not_friends`, `account_disabled` | Tidak diizinkan |
| 404 | `not_found` | Resource tidak ada |
| 405 | `method_not_allowed` | Method salah |
| 409 | `username_taken`, `phone_taken`, `already_friends`, `incoming_request` | Konflik |
| 413 | `file_too_large` | Upload terlalu besar |
| 422 | `validation_error`, `invalid_*`, `empty_message` | Validasi gagal |
| 429 | `rate_limited` | Terlalu banyak request |
| 500 | `storage_error` | Kesalahan server |

Rate limit memakai fixed window per IP/user. Beberapa batas penting: login 30/menit per IP dan 10/menit per akun, pencarian 60/menit, upload 60/jam, sync kontak 12/jam, permintaan teman 40/jam.
