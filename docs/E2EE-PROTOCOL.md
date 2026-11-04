# Protokol E2EE NusaChat ("Signal-setara", implementasi sendiri)

Target: jaminan setara Signal — kerahasiaan per device, forward secrecy,
post-compromise healing (lewat DH ratchet), autentikasi pengirim grup —
dengan kode protokol milik sendiri di atas primitif standar.

## Aturan keras

1. **Primitif HANYA dari library teruji, tidak pernah tulis sendiri:**
   X25519, Ed25519, HKDF-SHA256 (BouncyCastle), AES-256-GCM (javax.crypto).
2. **Server tidak pernah melihat plaintext maupun kunci privat.** Server
   menyimpan: bundle kunci publik, ciphertext, metadata (siapa→siapa,
   id pesan). Pelanggaran aturan ini = bug kritis.
3. Setiap perubahan protokol WAJIB memperbarui dokumen ini + vektor uji.
   Review kripto eksternal disarankan sebelum klaim "final".

## Kunci per device

| Kunci | Algoritma | Simpan di | Naik ke server |
|---|---|---|---|
| Identity signing | Ed25519 | HP (encrypted prefs), tidak pernah keluar | publik saja |
| Identity DH | X25519 | HP, tidak pernah keluar | publik saja |
| Signed prekey | X25519, rotasi ≤30 hari | HP | publik + tanda tangan Ed25519 |
| One-time prekey | X25519, batch 100, isi ulang bila <20 | HP | publik; dihapus server setelah dipakai 1x |

Penyimpangan dari Signal (disengaja, didokumentasikan): identity DH dan
identity signing adalah keypair TERPISAH (Signal memakai satu materi
kunci untuk keduanya). Lebih simpel, biaya: satu kunci publik ekstra.
Tidak ada sealed sender, tidak ada Noise pipe — handshake inline di
pesan pertama (lihat di bawah). Encoding JSON (bukan protobuf).

## X3DH (sesi pairwise baru)

Inisiator A mengambil bundle B: `{id_dh_B, spk_B, sig_spk_B, otpk_B?}`.

1. Verifikasi `sig_spk_B` dengan `id_sign_B`. Gagal = batal.
2. Ephemeral `ek_A` acak. DH1 = DH(id_dh_A, spk_B), DH2 = DH(ek_A,
   id_dh_B), DH3 = DH(ek_A, spk_B), [DH4 = DH(ek_A, otpk_B) bila ada].
3. `SK = HKDF(salt=0, IKM=DH1||DH2||DH3[||DH4], info="NusaX3DHv1")`.
4. Hapus `ek_A` dan DH sementara dari memori segera.
5. Pesan pertama membawa header `{id_dh_A, ek_A_pub, spk_id_B,
   otpk_id_B?, ratchet_pub_A}` + ciphertext pertama.

B membangun SK yang sama daribundle-nya sendiri, lalu kedua pihak masuk
Double Ratchet dengan SK sebagai root key awal. Jika `otpk_B` dipakai,
server sudah menghapusnya saat bundle diambil (aturannya: server hapus
saat serah — replay bundle yang sama tidak memberi otpk yang sama).

## Double Ratchet (tiap sesi pairwise)

Standar: root chain + sending/receiving chain (HMAC-SHA256),
DH ratchet tiap ada `ratchet_pub` baru dari lawan,
message key = HMAC(chain, 0x01) lalu chain = HMAC(chain, 0x02),
ciphertext = AES-256-GCM(nonce acak 96-bit, AAD = header).
Kunci pesan dihapus segera setelah dipakai/dilewati.

Batas: cache kunci terlewati maks 1000 per sesi, pesan tertinggal
>1000 atau dari rantai terlalu tua = tolak (minta kirim ulang, bukan
bocorkan apa pun). Header `{ratchet_pub, prev_len, msg_no}` plaintext
(seperti Signal — metadata, bukan isi).

## Grup: Sender Key

Tiap device pengirim punya sender key sendiri: chain key acak 32 byte
+ keypair signing Ed25519. Pertama kali kirim ke grup: distribusikan
SenderKeyDistributionMessage ke TIAP device anggota via sesi pairwise.
Pesan grup = `{sender_device, key_index}` + AES-GCM(chain) +
tanda tangan Ed25519. Chain maju tiap pesan (FS per pengirim).
Anggota keluar → semua sender key dibuang + distribusi ulang
(post-compromise healing grup).

## Pesan di kabel (amplop v2, JSON)

Badan pesan `text` adalah salah satu amplop ini (server buram penuh):

```json
{"v":2,"kind":"pairwise","from":"dev_A",
 "parts":{"dev_B":{"x3dh":{"idh":"..","ek":"..","spk":1,"otk":2},
 "dr":{"rp":"..","pl":0,"n":0},"nonce":"..","ct":".."}}}
{"v":2,"kind":"senderkey","from":"dev_A","idx":5,
 "nonce":"..","ct":"..","sig":".."}
{"v":2,"kind":"skdm","from":"dev_A","to":"dev_B","conv":123,
 "part":{ ...bentuk part pairwise... }}
```

`x3dh` hanya di pesan pertama sesi (responder merekonstruksi SK);
plaintext pairwise = UTF-8 mentah, plaintext SKDM =
`{"ck":"..","sp":".."}`, isi distribusi sender key.
Server meneruskan buram, menunggu ack, lalu hapus. `kind` hanya
routing/UX — isi tak bisa dibedakan server.

Status Fase 1 (SELESAI, per-user bukan per-device — multi-device di Fase 3):
`messages` + `message_recipients` = antrean store-forward; TTL 30 hari
pesan tak terkirim, tenggang 24 jam setelah semua penerima ack
(`POST /conversations/ack`, idempotent), lalu disapu permanen oleh
tick prune Erlang (`nusa_fanout`, tiap 5 menit). `GET messages`,
unread, preview, dan search hanya melihat sisa antrean yang belum
kedaluwarsa; `read`/`message_reads` tetap 200 untuk pesan tersapu
(tanda baca dari anggota); `reply_to` dan `forward` menerima fallback
isi dari cache perangkat. Penyapu diverifikasi langsung (baris
kedaluwarsa + penerima + event lama hilang; log `relay sweep ok`).

Status Fase 2 (SELESAI, single-device per user — multi-device Fase 3):
klien provision device + prekey saat login (`SessionCrypto.provision`,
rotasi SPK 30 hari, isi ulang OTPK <20); 1-1 = X3DH + Double Ratchet
per pasangan device (tie-break identity-DH bila inisiasi ganda);
grup = SenderKey + SKDM via pesan relay `silent` (rotasi otomatis
bila himpunan anggota berubah); dekrip sinkron murni lokal dengan
fallback legacy → plaintext → placeholder. Batas badan server 8192
karakter. Kesenjangan jujur: non-teks (media/lokasi) belum v2;
satu pesan pertama race inisiasi bisa placeholder; anggota grup
tanpa device v2 tak bisa baca pesan v2; gema sendiri lintas restart
tampil "[Pesan terkirim]". Diverifikasi: vektor interop JVM +
dekrip silang implementasi independen (lampiran tes: `EnvelopeV2Test`,
`CryptoVectorsTest` kasus `envelope*`).

## Backup (ringkas; detail diquip Fase 4)

Kunci backup acak 256-bit dibuat di HP. Mode kunci 64 digit:
tampil sebagai 64 digit desimal (`SHA-256(digit)` = kunci), server tak
pernah lihat. Mode passphrase: Argon2id → KEK → bungkus kunci backup
(AES-GCM). Blob = header versi + ciphertext dump Room (+media).
Tanpa HSM: mode passphrase tahan sekuat passphrase (jujur, bukan
setara WA di titik ini).

## Vektor uji (wajib hijau)

`mobile/app/src/test/.../CryptoVectorsTest`: X3DH deterministik
(kunci tetap → SK tetap, cocok lintas implementasi tes), ratchet
maju 1-2-3 pesan (ciphertext deterministik dengan nonce tetap di tes),
out-of-order (terima 3-1-2 tetap dekrip), grup 3 device
(distribusi + kirim + anggota-keluar-rotasi). Ditambah round-trip
acak tiap run.
