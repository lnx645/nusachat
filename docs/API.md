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

## Konfigurasi

### GET `/config`

Info aplikasi, fitur, batas, dan `ws_url`.

### GET `/config/ping`

```json
{ "ok": true, "data": { "pong": true, "db": "sqlite", "server_time": "...", "version": "0.1.0" } }
```

## Pengguna 🔒

| Method | Path | Keterangan |
| --- | --- | --- |
| GET | `/users/me` | Profil sendiri |
| POST/PATCH | `/users/update` | Update profil |
| GET | `/users/search?q=rina` | Cari pengguna (min. 2 karakter) |
| GET | `/users/show?id=5` | Profil pengguna lain |
| POST | `/users/password` | Ganti password |
| POST | `/users/presence` | Kirim heartbeat/lokasi |

Field `users/update`: `display_name`, `bio`, `gender` (`male`/`female`/`other`), `birthday` (`YYYY-MM-DD`), `avatar_url`, `discoverable`, `phone`.

```json
{ "lat": -6.2, "lng": 106.816 }
```

`users/presence` tanpa `lat`/`lng` hanya memperbarui `last_seen_at`.

## Teman 🔒

| Method | Path | Body/Query |
| --- | --- | --- |
| GET | `/friends` | — |
| GET | `/friends/requests` | — |
| POST | `/friends/send` | `user_id` |
| POST | `/friends/respond` | `request_id`, `action` = `accept`/`decline` |
| POST/DELETE | `/friends/remove` | `user_id` |
| GET | `/friends/status?user_id=5` | — |

`GET /friends/requests` mengembalikan `incoming` dan `outgoing`.

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
| POST | `/conversations/read` | `id`, `message_id` |
| POST | `/conversations/typing` | `id` |
| POST | `/conversations/add_members` | `id`, `user_ids` |
| POST | `/conversations/remove_member` | `id`, `user_id` |
| POST | `/conversations/mute` | `id`, `muted` |

Chat langsung hanya bisa dibuat dengan teman. Grup maksimal 50 anggota dan hanya owner yang bisa menambah anggota.

### Kirim pesan

```json
{
  "id": 1,
  "type": "text",
  "body": "Halo!",
  "client_id": "7a8f0e6e-5d1e-4f0e-9d7c-2a4b6c8d0e1f"
}
```

Field tambahan: `reply_to_id`, `attachment_url`, `attachment_meta`. Untuk `type` selain `text`, `attachment_url` wajib. Tipe yang didukung: `text`, `image`, `file`, `audio`, `video`.

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

## Momen 🔒

| Method | Path | Body/Query |
| --- | --- | --- |
| GET | `/moments` | `before_id?`, `limit?` |
| GET | `/moments/show?id=1` | — |
| POST | `/moments/create` | `body?`, `media?` |
| POST/DELETE | `/moments/delete` | `id` |
| POST | `/moments/like` | `id` |
| POST/DELETE | `/moments/unlike` | `id` |
| GET | `/moments/comments?id=1` | — |
| POST | `/moments/comment` | `id`, `body` |

`media` adalah array URL hasil upload, maksimal 9. Momen minimal berisi teks atau satu media. Feed berisi momen sendiri dan teman.

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
    "ws_url": "ws://localhost:8090"
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

## Voice call 🔒

| Method | Path | Body |
| --- | --- | --- |
| POST | `/calls/offer` | `to_user_id`, `sdp` |
| POST | `/calls/answer` | `call_id`, `sdp` |
| POST | `/calls/ice` | `call_id`, `candidate` (`{candidate, sdpMid, sdpMLineIndex}`) |
| POST | `/calls/reject` | `call_id` |
| POST | `/calls/end` | `call_id` |
| GET | `/calls/incoming` | — |

Signaling lewat outbox realtime (`call_offer`, `call_answer`, `call_ice`, `call_end`) + FCM wake-up untuk `call_offer`. Media audio peer-to-peer WebRTC (STUN `stun:stun.l.google.com:19302`). Status: `ringing` → `active` → `ended`/`rejected`.

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
  "client_id": "…",
  "status": "read",
  "revoked": false,
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

URL: `ws://<host>:8090/?token=<access_token>`

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

Tipe event: `message`, `read`, `typing`, `message_revoked`, `friend_request`, `friend_accepted`, `conversation_updated`, `moment_new`, `moment_liked`, `moment_comment`.

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
