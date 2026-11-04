# Tindak Lanjut: 6 FAIL Smoke Test (analisis + perintah)

Hasil: lint OK, ~150 uji hijau, 6 FAIL beruntun mulai dari `register resettable`
(429). Semua FAIL berikutnya adalah **efek domino** — user `resettable` tidak
tercipta sehingga forgot/reset/sessions ikut gagal. Satu-satunya akar yang
perlu diselidiki: **register 429**.

## Kesimpulan analisis (baca-kode, belum eksekusi)

1. **Bukan dari perubahan `client_ip()`.** Helper `request()` di
   `tools/smoke.py:24-56` tidak pernah mengirim header `X-Forwarded-For`,
   jadi dulu maupun sekarang semua request terbaca sebagai `127.0.0.1`.
   Perilaku throttle register identik sebelum/sesudah patch.
2. **Penyebab paling mungkin: `rate_limits` tidak di-reset antar run.**
   `REGISTER_RATE_LIMIT=20/jam/IP`. Suite ini memanggil `/auth/register`
   beberapa kali; run kedua tanpa `migrate --fresh` akan 429 tepat di
   register ketiga (`resettable`), lalu semua uji sesudahnya gagal beruntun.
   Pola FAIL (mulai di tengah suite, semua yang butuh user baru gagal)
   cocok dengan ini.
3. **`forgot known issues token` FAIL kemungkinan masalah env, bukan kode.**
   `Auth::forgot()` hanya mengembalikan `reset_token` bila
   `APP_ENV !== 'production'`. Uji mengharapkan token tapi dapat
   `{'sent': True}` → artinya `.env` yang dipakai ber-`APP_ENV=production`.
   Tidak terkait patch (fail-closed `assert_secret` tidak terpicu —
   terbukti semua uji lain lolos dengan secret yang sama).

## Perintah verifikasi (jalankan berurutan, pakai prefix `!`)

```text
! grep -E "^(APP_ENV|APP_SECRET|TRUSTED_PROXIES|CORS_ORIGINS)" backend/.env
! cd backend && php tools/migrate.php --fresh && php tools/seed.php
! cd backend && python3 tools/smoke.py
```

Ekspektasi bila hipotesis benar: `register resettable` OK dan semua FAIL
turunannya hilang. Bila `forgot known issues token` masih FAIL, cek langkah 1:
`APP_ENV` harus `development` untuk smoke (atau sesuaikan ekspektasi uji).

## Bila `register resettable` masih 429 setelah fresh migrate

```text
! cd backend && sqlite3 storage/nusachat.sqlite "SELECT key_hash, count, datetime(window_start,'unixepoch') FROM rate_limits ORDER BY window_start DESC LIMIT 5;"
```

- Bila `count` naik 1 per request dari satu IP → throttle bekerja normal;
  artinya suite melebihi 20 register/jam → naikkan sementara
  `REGISTER_RATE_LIMIT=200` di `.env` khusus uji, jangan commit.
- Bila `count` melonjak >1 per request → ada double-hit (retry client);
  laporkan outputnya untuk diperbaiki.

## Bila semua hijau

```text
! cd backend && python3 tools/ws_smoke.py
```

Lalu lanjutkan sisa backlog audit yang belum dikerjakan: tiket WS sekali
pakai + `wss://` wajib (M2), throttle atomik DB (L2), `_build/` keluar dari
git (L6), rotasi secret dev yang sempat ke-commit.
