<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Verifikasi Google ID token (RS256) tanpa dependensi eksternal.
 * Sertifikat Google di-cache 12 jam agar tidak membebani tiap login.
 */
final class Googleauth
{
    private const CERTS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
    private const CACHE_TTL = 43200;

    /**
     * @param string[] $audiences client_id yang diterima (web + android).
     * @return array{sub:string,email:string,email_verified:bool,name:string,picture:string}
     * @throws RuntimeException bila token tidak valid / layanan tak terjangkau.
     */
    public static function verifyIdToken(string $idToken, array $audiences): array
    {
        $parts = explode('.', $idToken);
        if (count($parts) !== 3) {
            throw new RuntimeException('Format ID token salah.');
        }
        [$headB64, $bodyB64, $sigB64] = $parts;
        $header = json_decode(self::b64urlDecode($headB64), true);
        $claims = json_decode(self::b64urlDecode($bodyB64), true);
        $sig = self::b64urlDecode($sigB64);
        if (!is_array($header) || !is_array($claims) || $sig === '') {
            throw new RuntimeException('Format ID token salah.');
        }
        if (($header['alg'] ?? '') !== 'RS256' || empty($header['kid'])) {
            throw new RuntimeException('Algoritma ID token tidak didukung.');
        }
        $now = time();
        if (isset($claims['exp']) && (int) $claims['exp'] < $now - 60) {
            throw new RuntimeException('ID token kedaluwarsa.');
        }
        $iss = (string) ($claims['iss'] ?? '');
        if ($iss !== 'accounts.google.com' && $iss !== 'https://accounts.google.com') {
            throw new RuntimeException('Penerbit ID token tidak dikenal.');
        }
        if (!in_array((string) ($claims['aud'] ?? ''), $audiences, true)) {
            throw new RuntimeException('ID token bukan untuk aplikasi ini.');
        }
        $sub = (string) ($claims['sub'] ?? '');
        if ($sub === '') {
            throw new RuntimeException('ID token tanpa identitas.');
        }
        $pem = self::pemForKid((string) $header['kid']);
        $key = openssl_pkey_get_public($pem);
        if ($key === false) {
            throw new RuntimeException('Kunci Google tidak valid.');
        }
        $ok = openssl_verify($headB64 . '.' . $bodyB64, $sig, $key, OPENSSL_ALGO_SHA256);
        if ($ok !== 1) {
            throw new RuntimeException('Tanda tangan ID token tidak valid.');
        }
        return [
            'sub' => $sub,
            'email' => (string) ($claims['email'] ?? ''),
            'email_verified' => ($claims['email_verified'] ?? false) === true || ($claims['email_verified'] ?? '') === 'true',
            'name' => (string) ($claims['name'] ?? ''),
            'picture' => (string) ($claims['picture'] ?? ''),
        ];
    }

    private static function pemForKid(string $kid): string
    {
        $certs = self::cachedCerts();
        if (!isset($certs[$kid])) {
            // Rotasi kunci: segarkan sekali lalu coba lagi.
            $certs = self::fetchCerts(true);
        }
        if (!isset($certs[$kid])) {
            throw new RuntimeException('Kunci Google tidak ditemukan.');
        }
        return self::jwkToPem($certs[$kid]);
    }

    private static function cachedCerts(): array
    {
        $file = self::cacheFile();
        if (is_file($file) && filemtime($file) > time() - self::CACHE_TTL) {
            $data = json_decode((string) file_get_contents($file), true);
            if (is_array($data) && $data !== []) {
                return $data;
            }
        }
        return self::fetchCerts(true);
    }

    private static function fetchCerts(bool $write): array
    {
        $ctx = stream_context_create(['http' => ['timeout' => 8]]);
        $raw = @file_get_contents(self::CERTS_URL, false, $ctx);
        if ($raw === false) {
            // Pakai cache basi bila Google tak terjangkau.
            $file = self::cacheFile();
            if (is_file($file)) {
                $data = json_decode((string) file_get_contents($file), true);
                if (is_array($data) && $data !== []) {
                    return $data;
                }
            }
            throw new RuntimeException('Tidak dapat menghubungi Google. Coba lagi.');
        }
        $doc = json_decode($raw, true);
        $keys = [];
        foreach ((array) ($doc['keys'] ?? []) as $k) {
            if (($k['kty'] ?? '') === 'RSA' && isset($k['kid'], $k['n'], $k['e'])) {
                $keys[$k['kid']] = $k;
            }
        }
        if ($keys === []) {
            throw new RuntimeException('Respons kunci Google kosong.');
        }
        if ($write) {
            @file_put_contents(self::cacheFile(), json_encode($keys));
        }
        return $keys;
    }

    private static function cacheFile(): string
    {
        $dir = defined('APPPATH') ? APPPATH . 'cache' : sys_get_temp_dir();
        if (!is_dir($dir)) {
            $dir = sys_get_temp_dir();
        }
        return rtrim($dir, '/') . '/google_certs.json';
    }

    /** JWK RSA (n, e) -> PEM PKCS#8. */
    private static function jwkToPem(array $jwk): string
    {
        $n = self::b64urlDecode($jwk['n']);
        $e = self::b64urlDecode($jwk['e']);
        // INTEGER positif: awali 0x00 bila bit tertinggi menyala.
        if ((ord($n[0]) & 0x80) !== 0) {
            $n = "\x00" . $n;
        }
        if ((ord($e[0]) & 0x80) !== 0) {
            $e = "\x00" . $e;
        }
        $rsaSeq = self::derSeq(
            self::derInt($n) . self::derInt($e)
        );
        $pemBody = self::derSeq(
            self::derSeq(self::derOid('1.2.840.113549.1.1.1') . "\x05\x00") .
            self::derBitString($rsaSeq)
        );
        return "-----BEGIN PUBLIC KEY-----\n" .
            chunk_split(base64_encode($pemBody), 64, "\n") .
            "-----END PUBLIC KEY-----\n";
    }

    private static function derLen(int $len): string
    {
        if ($len < 128) {
            return chr($len);
        }
        $bytes = '';
        while ($len > 0) {
            $bytes = chr($len & 0xFF) . $bytes;
            $len >>= 8;
        }
        return chr(0x80 | strlen($bytes)) . $bytes;
    }

    private static function derSeq(string $body): string
    {
        return "\x30" . self::derLen(strlen($body)) . $body;
    }

    private static function derInt(string $raw): string
    {
        return "\x02" . self::derLen(strlen($raw)) . $raw;
    }

    private static function derBitString(string $body): string
    {
        return "\x03" . self::derLen(strlen($body) + 1) . "\x00" . $body;
    }

    private static function derOid(string $dotted): string
    {
        $parts = array_map('intval', explode('.', $dotted));
        $first = chr(40 * $parts[0] + $parts[1]);
        $rest = '';
        foreach (array_slice($parts, 2) as $p) {
            $enc = chr($p & 0x7F);
            $p >>= 7;
            while ($p > 0) {
                $enc = chr(0x80 | ($p & 0x7F)) . $enc;
                $p >>= 7;
            }
            // Set bit lanjutan pada semua oktet kecuali terakhir.
            for ($i = 0; $i < strlen($enc) - 1; $i++) {
                $enc[$i] = chr(ord($enc[$i]) | 0x80);
            }
            $rest .= $enc;
        }
        $body = $first . $rest;
        return "\x06" . self::derLen(strlen($body)) . $body;
    }

    private static function b64urlDecode(string $raw): string
    {
        $pad = strlen($raw) % 4;
        if ($pad) {
            $raw .= str_repeat('=', 4 - $pad);
        }
        return base64_decode(strtr($raw, '-_', '+/'), true) ?: '';
    }
}
