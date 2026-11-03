<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Minimal HS256 JWT implementation (no external dependency).
 */
final class Jwt
{
    private static function b64url(string $raw): string
    {
        return rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');
    }

    private static function b64urlDecode(string $raw): string
    {
        $pad = strlen($raw) % 4;
        if ($pad) {
            $raw .= str_repeat('=', 4 - $pad);
        }
        return base64_decode(strtr($raw, '-_', '+/'), true) ?: '';
    }

    public static function encode(array $payload, string $secret): string
    {
        $header = ['typ' => 'JWT', 'alg' => 'HS256'];
        $segments = self::b64url(json_encode($header, JSON_UNESCAPED_SLASHES))
            . '.' . self::b64url(json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
        $signature = hash_hmac('sha256', $segments, $secret, true);
        return $segments . '.' . self::b64url($signature);
    }

    public static function decode(string $token, string $secret): ?array
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return null;
        }
        [$head, $body, $sig] = $parts;
        $expected = hash_hmac('sha256', $head . '.' . $body, $secret, true);
        if (!hash_equals($expected, self::b64urlDecode($sig))) {
            return null;
        }
        $payload = json_decode(self::b64urlDecode($body), true);
        if (!is_array($payload)) {
            return null;
        }
        $now = time();
        if (isset($payload['exp']) && $now >= (int) $payload['exp']) {
            return null;
        }
        if (isset($payload['nbf']) && $now < (int) $payload['nbf']) {
            return null;
        }
        return $payload;
    }
}
