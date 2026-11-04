<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Token_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    /**
     * Create an access/refresh token pair for a user.
     */
    public function issue(array $user, string $device, array $app): array
    {
        $jti = bin2hex(random_bytes(16));
        $secret = bin2hex(random_bytes(32));
        $refresh = $jti . '.' . $secret;
        $now = time();

        $this->store->insert('tokens', [
            'user_id' => (int) $user['id'],
            'jti' => $jti,
            'refresh_hash' => hash('sha256', $secret),
            'device' => mb_substr($device, 0, 120),
            'expires_at' => gmdate('Y-m-d\TH:i:s\Z', $now + $app['refresh_ttl']),
            'created_at' => now_iso(),
            'last_used_at' => now_iso(),
        ]);

        $access = Jwt::encode([
            'sub' => (int) $user['id'],
            'username' => $user['username'],
            'tv' => (int) $user['token_version'],
            'typ' => 'access',
            'iat' => $now,
            'exp' => $now + $app['access_ttl'],
            'jti' => bin2hex(random_bytes(8)),
        ], $app['secret']);

        return [
            'access_token' => $access,
            'refresh_token' => $refresh,
            'token_type' => 'Bearer',
            'expires_in' => (int) $app['access_ttl'],
            'refresh_expires_in' => (int) $app['refresh_ttl'],
        ];
    }

    /**
     * Validate and rotate a refresh token. Returns the token row or null.
     */
    public function consumeRefresh(string $refresh): ?array
    {
        $result = $this->consumeRefreshStrict($refresh);
        return $result['status'] === 'ok' ? $result['row'] : null;
    }

    /**
     * Strict variant: bedakan 'ok' vs 'invalid' vs 'reused'.
     *
     * 'reused' = jti yang sudah DIROTASI dipakai lagi → indikasi pencurian
     * token. Seluruh keluarga token user langsung dicabut agar penyerang
     * tidak bisa memakai hasil curian.
     *
     * Token yang dicabut eksplisit (logout / cabut sesi) BUKAN reuse: klien
     * basi yang retry tidak boleh memusnahkan sesi lain user. Baris lama
     * tanpa revoked_reason diperlakukan sama (invalid, tanpa nuke).
     *
     * @return array{status:string, row?:array}
     */
    public function consumeRefreshStrict(string $refresh): array
    {
        $parts = explode('.', $refresh);
        if (count($parts) !== 2) {
            return ['status' => 'invalid'];
        }
        [$jti, $secret] = $parts;
        $row = $this->store->one('SELECT * FROM tokens WHERE jti = ?', [$jti]);
        if (!$row) {
            return ['status' => 'invalid'];
        }
        if (strtotime($row['expires_at']) < time()) {
            return ['status' => 'invalid'];
        }
        if (!hash_equals($row['refresh_hash'], hash('sha256', $secret))) {
            return ['status' => 'invalid'];
        }
        if ($row['revoked_at'] !== null) {
            if (($row['revoked_reason'] ?? null) === 'rotated') {
                $this->revokeAll((int) $row['user_id']);
                return ['status' => 'reused', 'row' => $row];
            }
            return ['status' => 'invalid'];
        }
        $this->store->update(
            'tokens',
            ['revoked_at' => now_iso(), 'revoked_reason' => 'rotated'],
            'id = ?',
            [(int) $row['id']]
        );
        return ['status' => 'ok', 'row' => $row];
    }

    public function touchLastUsed(string $jti): void
    {
        $this->store->update('tokens', ['last_used_at' => now_iso()], 'jti = ?', [$jti]);
    }

    /**
     * Sesi aktif (refresh token hidup) untuk layar "Perangkat saya".
     *
     * @return array<int, array>
     */
    public function listActive(int $userId): array
    {
        return $this->store->all(
            'SELECT jti, device, created_at, last_used_at, expires_at FROM tokens
             WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?
             ORDER BY id DESC LIMIT 25',
            [$userId, now_iso()]
        );
    }

    public function revokeByJti(int $userId, string $jti): bool
    {
        return $this->store->update(
            'tokens',
            ['revoked_at' => now_iso(), 'revoked_reason' => 'revoked'],
            'user_id = ? AND jti = ? AND revoked_at IS NULL',
            [$userId, $jti]
        ) > 0;
    }

    public function revokeRefresh(string $refresh): void
    {
        $parts = explode('.', $refresh);
        if (count($parts) !== 2) {
            return;
        }
        $this->store->update(
            'tokens',
            ['revoked_at' => now_iso(), 'revoked_reason' => 'revoked'],
            'jti = ? AND revoked_at IS NULL',
            [$parts[0]]
        );
    }

    public function revokeAll(int $userId): void
    {
        $this->store->update(
            'tokens',
            ['revoked_at' => now_iso(), 'revoked_reason' => 'revoked'],
            'user_id = ? AND revoked_at IS NULL',
            [$userId]
        );
    }

    public function prune(): void
    {
        $this->store->delete('tokens', 'expires_at < ?', [gmdate('Y-m-d\TH:i:s\Z', time() - 86400)]);
    }
}
