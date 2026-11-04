# NusaChat

Aplikasi chat orisinal bergaya messenger: obrolan langsung dan grup, momen, kontak, serta orang terdekat. Backend memakai CodeIgniter 3 (fork komunitas `pocketarc`) dengan PHP 8.2+, sedangkan klien Android memakai Jetpack Compose.

> NusaChat bukan aplikasi resmi WeChat, MiChat, atau merek lain. Semua kode, nama, warna, dan aset dibuat sendiri. Kemiripan yang ada hanya pada pola UX umum aplikasi chat.

## Fitur

- Autentikasi JWT HS256 + refresh token berputar, logout mencabut sesi lewat `token_version`. Ganti kata sandi dari aplikasi.
- Chat langsung dan grup, indikator mengetik, status terkirim/dibaca, balas pesan, tarik pesan, edit pesan, reaksi emoji, teruskan pesan, sematkan pesan, arsip obrolan, cari pesan, mention `@username`, kirim ulang saat gagal, lampiran foto/video/audio/file, dan pengiriman idempoten lewat `client_id`.
- Grup: buat, tambah/keluarkan anggota (owner + admin), keluar grup, bisu notifikasi, info anggota, undang via tautan, persetujuan admin, role admin.
- Daftar teman, pencarian pengguna, permintaan pertemanan, hapus teman, blokir/buka blokir, hapus akun permanen, dan sinkronisasi kontak HP (izin `READ_CONTACTS`).
- Orang terdekat berbasis koordinat kasar (Haversine), bukan pelacakan real-time.
- Momen teks + foto/video (maks 9), suka, komentar, hapus, posting ulang (reshare), bagikan ke chat, dan pagination "muat momen lama".
- Story 24 jam: teks + media (maks 4), strip story di Momen, viewer geser, hapus otomatis saat kedaluwarsa.
- Dompet koin: top up, kirim ke teman, riwayat transaksi (koin demo, bukan uang sungguhan).
- Profil: nama, bio, HP, gender, tanggal lahir, discoverable, dan foto profil.
- Upload gambar/audio/video/PDF ke `storage/uploads`.
- Gateway WebSocket PHP CLI tanpa dependency eksternal.
- Push notification FCM: data-only payload, channel Pesan/Panggilan, registrasi token via `/push/register`.
- Voice/video call 1-1 via WebRTC (STUN Google) + signaling REST `/calls/*` + realtime `call_*`; tombol telepon dan video di chat langsung.
- Panggilan grup suara (mesh WebRTC): buat room dari info grup, gabung dari notifikasi, daftar partisipan realtime.
- Klien Android Compose dengan 4 tab: Obrolan, Kontak, Jelajah, Saya.

## Stack

| Bagian | Teknologi |
| --- | --- |
| Backend | PHP 8.2+, CodeIgniter 3.4.7, PDO, SQLite (dev) / MySQL (produksi) |
| Auth | JWT HS256, refresh token opaque, `password_hash` |
| Realtime | PHP CLI WebSocket daemon, tabel `realtime_events` |
| Android | Kotlin, Jetpack Compose, OkHttp, Coil, SharedPreferences |
| Build Android | AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01 |

Backend tidak memakai Composer dan tidak membutuhkan ekstensi PHP di luar `pdo`, `pdo_sqlite`/`pdo_mysql`, `json`, `mbstring`, dan `fileinfo`.

## Struktur

```
nusachat/
├── backend/
│   ├── application/          # controller, model, library, helper
│   ├── database/             # skema SQLite dan MySQL
│   ├── storage/uploads/      # file hasil upload
│   ├── tools/                # migrate, seed, smoke test, WS gateway
│   ├── router.php            # router untuk PHP built-in server
│   └── .env.example
├── docs/
│   ├── API.md
│   └── ARCHITECTURE.md
└── mobile/
    └── app/src/main/java/id/nusachat/app/
```

## Menjalankan backend

```bash
cd backend
cp .env.example .env
php tools/migrate.php --fresh
php tools/seed.php
php -S 127.0.0.1:8080 router.php
```

Di terminal lain, jalankan gateway WebSocket:

```bash
cd backend
php tools/ws-server.php
```

Akun demo (password `password123`): `nusa`, `sari`, `budi`, `dewi`.

Uji end-to-end:

```bash
cd backend
python3 tools/smoke.py      # REST
python3 tools/ws_smoke.py   # WebSocket
```

Status terakhir: REST **127/127** dan WebSocket **6/6** lolos.

## Menjalankan Android

Debug build saat ini diarahkan ke host lewat LAN agar bisa dipakai di perangkat fisik:

- API: `http://192.168.70.92:8080/api/v1`
- WebSocket: `ws://192.168.70.92:8090`

Ubah `API_BASE_URL` dan `WS_URL` di `mobile/app/build.gradle.kts` jika IP host berubah. Untuk Android Emulator, gunakan `http://10.0.2.2:8080/api/v1` dan `ws://10.0.2.2:8090`.

```bash
cd mobile
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pastikan HP dan komputer berada di jaringan yang sama, API listen di `0.0.0.0:8080` (bukan `127.0.0.1`), dan firewall mengizinkan TCP `8080` serta `8090`.

Status build: `assembleDebug` **berhasil** dan APK sudah diuji di perangkat Infinix X6532. Login, daftar teman, terima permintaan, kirim pesan, terima pesan realtime, unread badge, serta unggah/tampilkan gambar momen berjalan. Build berikutnya tinggal menjalankan perintah di atas.

## Konfigurasi

Salin `.env.example` menjadi `.env`. Variabel penting:

| Variabel | Keterangan |
| --- | --- |
| `APP_ENV` | `development` atau `production` |
| `APP_SECRET` | Kunci HS256; wajib diganti di produksi (minimal 32 byte acak) |
| `APP_URL` | URL publik untuk membentuk URL upload. Jika kosong atau berisi `localhost`/`127.0.0.1`, URL upload otomatis memakai host request (cocok untuk LAN/emulator) |
| `JWT_TTL` | Umur access token (detik), default 7 hari |
| `REFRESH_TTL` | Umur refresh token (detik), default 30 hari |
| `DB_DRIVER` | `sqlite` atau `mysql` |
| `DB_PATH` | Lokasi SQLite, default `storage/nusachat.sqlite` |
| `UPLOAD_MAX_MB` | Batas ukuran upload, default 25 MB |
| `WS_PUBLIC_URL` | URL WS yang dikembalikan `/config` dan `/sync` |
| `WS_BIND` | Alamat listen gateway, default `tcp://0.0.0.0:8090` |

Catatan: file `.env` memakai komentar `;`, bukan `#`, karena dibaca dengan `parse_ini_file()`.

## PHP 8

Kode backend memakai sintaks dan fitur PHP 8 yang sudah tersedia di runtime proyek (PHP 8.2.35):

- `declare(strict_types=1)` di seluruh kode aplikasi.
- Typed properties, union types, `mixed`, dan `never` untuk method yang selalu `exit`.
- `readonly` property pada `Store`.
- `match`, arrow function, first-class callable, `str_contains()`, dan `str_starts_with()`.
- Return type dan parameter type yang eksplisit di model, controller, helper, dan tool.

Kode tetap kompatibel dengan PHP 8.3/8.4. Fitur yang hanya ada di 8.3/8.4 belum dipakai karena runtime pengujian saat ini PHP 8.2.

## Keamanan dan batasan

- Jangan commit `.env` berisi `APP_SECRET` produksi.
- Gunakan HTTPS dan `wss://` di produksi (letakkan di belakang nginx/Caddy).
- SQLite cocok untuk demo; MySQL disarankan untuk produksi. Mode WAL, `busy_timeout`, dan retry singkat di gateway WS menjaga realtime tetap hidup saat API menulis bersamaan.
- Gateway WebSocket saat ini single-process dan polling database tiap 350 ms. Untuk trafik besar, pindah ke Workerman/Swoole atau pub/sub Redis.
- Push pakai FCM HTTP v1: taruh service account JSON di `backend/storage/fcm-service-account.json` + isi `FCM_PROJECT_ID` di `.env`. Tanpa itu, push nonaktif dan chat tetap jalan via WS saat app terbuka.
- Voice call butuh izin Mic + koneksi UDP/STUN (`stun:stun.l.google.com:19302`). Di LAN ketat, siapkan TURN sendiri. Video call butuh izin Kamera.
- Pesan teks terenkripsi end-to-end (AES-GCM + distribusi kunci RSA antar perangkat). Bukan ratchet produksi: tanpa forward secrecy dan rotasi otomatis.
- Dompet hanya koin demo (top up + transfer antar user), bukan uang sungguhan.
- `.env` contoh memakai CORS `*`; batasi origin di produksi.

## Lisensi dan originalitas

Proyek ini dibuat untuk pembelajaran. Tidak ada kode, aset, atau API milik MiChat/WeChat yang disalin. CodeIgniter 3 mengikuti lisensi MIT dari proyek aslinya.
