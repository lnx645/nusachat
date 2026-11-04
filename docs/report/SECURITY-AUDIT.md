# Audit Keamanan NusaChat — 2026-11-04

Cakupan: `backend/application` (Auth, Users, Conversations, Messages, Moments, Calls, Wallet, Uploads, Nearby, Reports), `libraries` (Jwt, Store, Googleauth, Fcm), `core/MY_Controller`, `helpers/api_helper`, `tools/ws-server.php`, `realtime/.../src` (auth WS, `nusa_nearby.erl`), Android (`SessionStore`, `ApiClient`, `ChatSocket`, `E2eeManager`, `NusaFirebaseService`), `database/schema.*`, `.env.example`, `docs/ARCHITECTURE.md`.

Metode: baca-kode statis, tanpa exploit aktif, tanpa sentuh prod. Tree saat audit kotor (30+ file modified + `Reports.php`, `SettingsScreen`, `VideoCallScreen` dkk. belum commit) — temuan di bawah mengacu ke HEAD + WIP yang terbaca.

## Ringkasan eksekutif

Fondasi di atas rata-rata proyek pembelajaran: JWT diverifikasi benar (`hash_equals`, cek `typ=access`, `exp`, `token_version`), refresh opaque di-hash + rotasi sekali pakai, semua query lewat `Store` prepared (`EMULATE_PREPARES=false`), upload cek `finfo` + nama acak + `0644`, Google ID token verifikasi RS256 lengkap, FCM data-only tanpa isi chat, E2EE AES-GCM IV acak + kunci di `EncryptedSharedPreferences`, Nearby dibulatkan 3 desimal.

Kelemahan praktis terbesar ada di **tepi**: token mobile di prefs biasa, CORS `*`, `client_ip()` percaya header proxy, PIN dompet tanpa throttle, token WS di query + `ws://`, secret fallback dev, dan `_build/` Erlang ke-commit.

## Temuan

### H1 — Token akses+refresh di SharedPreferences biasa
- Lokasi: `mobile/.../data/SessionStore.kt:14` (+ komentar TODO-nya sendiri).
- Dampak: di perangkat rooted / backup `adb`, token 7 hari + refresh 30 hari bisa dibaca langsung. E2EE sudah pakai `EncryptedSharedPreferences`, sesi belum.
- Fix: pindah ke `EncryptedSharedPreferences`/Keystore; `clear()` + `onNewToken` ikut dibersihkan saat logout; nonaktifkan `allowBackup` untuk prefs sesi atau set `backup_rules`.

### H2 — Throttle PIN dompet tidak ada → brute-force 4–6 digit
- Lokasi: `backend/.../Wallet.php:134-147` (`check_pin`), `:113-132` (`set_pin`/`old_pin`), `Wallet_model.php:121-128` (`password_verify` tanpa counter).
- Dampak: PIN 4 digit = 10.000 kombinasi; tanpa throttle per-akun, bisa ditebak lewat API login-valid. Transfer (`:41-73`) memanggil `assertPin` — sama-sama tanpa throttle.
- Fix: `throttle('pin:{id}', 5, 300)` + kunci sementara setelah N gagal + jeda eksponensial; audit log kegagalan; pertimbangkan PIN wajib sebelum transfer selalu (bukan sekali per sesi).

### H3 — CORS `*` + echo origin tanpa validasi
- Lokasi: `backend/application/config/app.php:13` (`CORS_ORIGINS=*`), `core/MY_Controller.php:42-50` (`Access-Control-Allow-Origin: *`, tanpa `Vary: Origin`, tanpa allowlist).
- Dampak: situs jahat bisa memanggil API dari browser korban; walau `Authorization` harus dicuri dulu, ini memperlebar permukaan CSRF/XSS-data-exfil.
- Fix: allowlist origin eksplisit di prod; jangan `*` bila `Authorization` dipakai; tambah `Vary: Origin`.

### H4 — `client_ip()` percaya `X-Forwarded-For` → bypass rate-limit
- Lokasi: `backend/.../helpers/api_helper.php:56-69`.
- Dampak: throttle register/login/refresh/upload semuanya keyed by `client_ip()`; penyerang bisa memalsukan header dan memutar IP tak terbatas (kecuali di belakang proxy yang menimpa header).
- Fix: hanya percaya `CF_CONNECTING_IP`/`X-Forwarded-For` bila request datang dari proxy tepercaya (`TRUSTED_PROXIES`); jika tidak, pakai `REMOTE_ADDR`.

### M1 — Secret fallback dev fail-open
- Lokasi: `config/app.php:8` (`?: 'dev-secret-change-me'`), `ws-server.php:70`, `Googleauth`/FCM default kosong.
- Dampak: bila `.env` hilang di prod, server tetap jalan dengan secret publik → semua JWT bisa ditempa.
- Fix: bila `APP_ENV=production` dan `APP_SECRET` < 32 byte / masih default → `exit(1)` saat boot (API + WS).

### M2 — Token WS di query string + `ws://` default
- Lokasi: `tools/ws-server.php:89-108` (verifikasi benar: `hash_equals`, cek `typ`, `exp`), `ChatSocket.kt:75` (`"/?token=" + token`), `.env.example:31` (`ws://`).
- Dampak: token akses 7 hari bocor di log proxy/server; tanpa `wss://` mudah disadap di LAN/WiFi publik.
- Fix: wajibkan `wss://` di prod (reverse proxy + upgrade), pertimbangkan tiket WS sekali pakai berumur 60 dtk; hindari log URL lengkap.

### M3 — Tidak ada deteksi reuse refresh-token
- Lokasi: `models/Token_model.php:57-76` (rotasi benar, revoke lama), `Auth.php:108-121`.
- Dampak: bila refresh dicuri dan dipakai duluan, pemilik sah hanya dapat "invalid" tanpa sinyal pencurian; keluarga token tidak dicabut.
- Fix: saat `jti` yang sudah `revoked_at` dipakai lagi → `revokeAll(user)` + naikkan `token_version` + log peringatan.

### M4 — Upload: MIME sudah benar, tapi eksekusi + konten aktif belum dikunci
- Lokasi: `controllers/.../Uploads.php:41-59` (bagus: `finfo`, allowlist, nama `random_bytes(16)`, `0644`).
- Dampak sisa: `pdf` bisa berisi JS/redirect; tidak ada `.htaccess`/`nginx` `php_engine off` yang terlihat untuk `storage/uploads`; `getimagesize` hanya untuk info.
- Fix: `storage/uploads/.htaccess` (`php_flag engine off`, `ForceType application/octet-stream` untuk yang bukan gambar), sajikan dengan `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff`; pertimbangkan hapus `application/pdf` atau render via viewer terisolasi.

### M5 — Reset password: token dikembalikan di non-prod + tanpa throttle per-akun
- Lokasi: `Auth.php:234-258` (`forgot` selalu 200 — bagus anti-enumerasi; tapi `:252-254` kembalikan `reset_token` bila bukan production), `:260-288` (cek `used_at`, expiry 15 mnt, `hash sha256` — bagus).
- Fix: pastikan `APP_ENV` tidak pernah `development` di prod; tambah throttle per-login selain per-IP; log + notifikasi ke pemilik akun.

### L1 — Host-header poisoning di URL upload
- Lokasi: `helpers/api_helper.php:97-100` (`$_SERVER['HTTP_HOST']` saat `APP_URL` loopback).
- Dampak: rendah (URL absolut untuk klien sendiri), tapi cache/poison bila di-reverse-proxy tanpa host allowlist.
- Fix: allowlist host upload (`UPLOAD_HOSTS`) atau selalu pakai `APP_URL` eksplisit di prod.

### L2 — Throttle fixed-window balapan + jendela register longgar
- Lokasi: `MY_Controller.php:180-198` (insert-lalu-update; dua request bersamaan bisa lolos 1 extra), `Auth.php:16` (20 register/jam/IP).
- Fix: `INSERT ... ON DUPLICATE KEY UPDATE count=count+1` (MySQL) / `ON CONFLICT ... DO UPDATE` (SQLite) atomik; CAPTCHA/OTP bila register disalahgunakan.

### L3 — Enumerasi akun sisa
- `check_username`, `username_taken`/`phone_taken` (409), pesan login "Incorrect username or password" (bagus, generik — pertahankan), `users/search` min 2 char + `users/show?id=` perlu pastikan hormati `discoverable`/blokir. Verifikasi: `search` harus filter non-discoverable kecuali sudah teman.

### L4 — Mobile transport & log
- `ApiClient.kt:33-38` tanpa pinning, timeout wajar; base URL LAN `http://` → wajib HTTPS di prod; pastikan token tidak di-`Logcat`/analytics; `ChatSocket` kirim `location.update` best-effort — server sudah validasi + clamp (`Nearby.php:25-29`, `nusa_nearby.erl` clamp radius, ETS stale 15 mnt — bagus).

### L5 — E2EE demo: jujur tapi terbatas (sudah didokumentasikan)
- `E2eeManager.kt:60-69` (AES-GCM IV 12B acak — benar), kunci sesi per-conversation di prefs terenkripsi, RSA-2048 OAEP untuk distribusi. Batasan yang harus tetap di UI/docs: tanpa forward secrecy/ratchet, media+metadata plaintext, fallback `decrypt` tampilkan ciphertext apa adanya (`:74,86-88`) bisa membingungkan — tampilkan placeholder "pesan terenkripsi" bila kunci belum ada.

### L6 — Higiene repo
- `realtime/.../_build/` (Cowboy/Ranch/Jsx ~70k baris) ikut terlacak → bloat + risiko supply-chain tak ter-review. Fix: `git rm --cached`, tambah `.gitignore`, pin versi rebar3.
- `.env.example` memuat `FCM_PROJECT_ID=dachat-6fd87` nyata — ganti placeholder. `APP_URL=http://localhost:8080` + IP LAN di `build.gradle.kts` gampang basi — pindah ke flavor/`local.properties`.
- Inkonsistensi docs-vs-WIP: `ARCHITECTURE.md` bilang "video call dihapus/403", tapi WIP ada `VideoCallScreen.kt` + perubahan `Calls.php`/`WebRtcCallManager`. Samakan sebelum rilis.

## Yang sudah bagus (pertahankan)

- JWT: `Jwt.php:33-56` (`hash_equals`, tolak bila `exp` lewat, `nbf` dihormati), `require_auth` cek `typ`, `sub`, `is_active`, `token_version` (`MY_Controller.php:128-155`).
- Password: `password_hash`/`verify`, batas 6–72, reserved username, normalisasi HP Indonesia.
- Otorisasi sampel yang dicek benar: `Messages::revoke/edit` cek pemilik + `isMember`; `Conversations::create_direct` cek blokir dua arah + harus teman; `Wallet::transfer` tolak self-transfer + cek user aktif + transaksi atomik (`UPDATE ... WHERE balance >= ?`, `Wallet_model.php:81-91`).
- Google: tolak `alg != RS256`/tanpa `kid`, cek `iss/aud/exp`, refresh cert sekali saat rotasi (`Googleauth.php:32-58`).
- `Store`: prepared + `EMULATE_PREPARES=false` di dua driver; Nearby clamp radius 1–100 km + koordinat divalidasi.

## Rekomendasi prioritas (quick-win dulu)

1. `SessionStore` → EncryptedSharedPreferences (H1).
2. Throttle + lockout PIN dompet (H2).
3. CORS allowlist + `Vary: Origin` (H3).
4. `client_ip()` hanya percaya proxy terdaftar (H4).
5. Fail-closed bila secret default di prod (M1).
6. `wss://` wajib + tiket WS pendek (M2).
7. Deteksi reuse refresh → cabut keluarga token (M3).
8. Kunci eksekusi `storage/uploads` + header nosniff (M4).
9. Keluarkan `_build/` dari git; rotasi secret dev yang sempat ke-commit (L6).

## Remediasi yang sudah diterapkan (pasca-audit)

- **H1 (token mobile)**: `SessionStore.kt` pindah ke `EncryptedSharedPreferences` (`nusachat.session.enc`) + migrasi sekali jalan dari file plaintext lama. Fallback ke prefs biasa bila Keystore tak tersedia.
- **H2 (PIN brute-force)**: `Wallet.php` — `assertPin()`, `check_pin`, `set_pin(old_pin)` kini lewat `checkPinThrottle()` (10x/5 mnt per user, 429 bila lewat).
- **H3 (CORS)**: `MY_Controller::send_cors()` hanya echo origin yang ada di allowlist `CORS_ORIGINS`; `*` = dev default; tambah `Vary: Origin`.
- **H4 (IP spoofing)**: `client_ip()` hanya percaya `CF_CONNECTING_IP`/`X-Forwarded-For` bila `REMOTE_ADDR` ada di `TRUSTED_PROXIES` (baru di `.env.example`); default = `REMOTE_ADDR`.
- **M1 (fail-closed)**: `MY_Controller::assert_secret()` 500 saat `APP_ENV=production` + secret lemah/default; `tools/ws-server.php` `exit(1)` dengan pesan jelas pada kondisi sama.
- **M3 (refresh reuse)**: `Token_model::consumeRefreshStrict()` → `Auth::refresh` cabut seluruh keluarga token + bump `token_version` + log bila `jti` revoked dipakai ulang.
- **M4 (upload)**: `backend/storage/uploads/.htaccess` — `php_flag engine off`, deny file eksekutabel, `ForceType application/octet-stream`, `nosniff`. Catatan: perlu padanan `location` bila prod pakai nginx (Apache-only).

Belum dikerjakan (butuh keputusan infra): tiket WS sekali pakai + `wss://` wajib (M2), throttle atomik DB (L2), `_build/` keluar dari git (L6), rotasi secret dev yang sempat ke-commit.

## Verifikasi ulang (tanpa exploit)

- `cd backend && php tools/migrate.php && python3 tools/smoke.py` (target tetap 127+/139), `python3 tools/ws_smoke.py`, `:app:assembleDebug`.
- Manual: login → refresh → revoke → pastikan token lama 401; PIN salah 6x → terkunci; register 21x/jam/IP → 429 walau header `X-Forwarded-For` dipalsukan; upload `.php` (rename `jpg`) → ditolak 422; buka file upload langsung → tidak dieksekusi; WS via `ws://` di prod → ditolak/dialihkan ke `wss://`.
