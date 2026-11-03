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
        $parts = explode('.', $refresh);
        if (count($parts) !== 2) {
            return null;
        }
        [$jti, $secret] = $parts;
        $row = $this->store->one('SELECT * FROM tokens WHERE jti = ?', [$jti]);
        if (!$row || $row['revoked_at'] !== null) {
            return null;
        }
        if (strtotime($row['expires_at']) < time()) {
            return null;
        }
        if (!hash_equals($row['refresh_hash'], hash('sha256', $secret))) {
            return null;
        }
        $this->store->update('tokens', ['revoked_at' => now_iso()], 'id = ?', [(int) $row['id']]);
        return $row;
    }

    public function revokeRefresh(string $refresh): void
    {
        $parts = explode('.', $refresh);
        if (count($parts) !== 2) {
            return;
        }
        $this->store->update('tokens', ['revoked_at' => now_iso()], 'jti = ? AND revoked_at IS NULL', [$parts[0]]);
    }

    public function revokeAll(int $userId): void
    {
        $this->store->update('tokens', ['revoked_at' => now_iso()], 'user_id = ? AND revoked_at IS NULL', [$userId]);
    }

    public function prune(): void
    {
        $this->store->delete('tokens', 'expires_at < ?', [gmdate('Y-m-d\TH:i:s\Z', time() - 86400)]);
    }
}
