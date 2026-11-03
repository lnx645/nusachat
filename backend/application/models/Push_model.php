<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Device push tokens for FCM delivery (one row per user+token).
 */
final class Push_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function save(int $userId, string $token, string $platform = 'android'): void
    {
        $now = now_iso();
        $existing = $this->store->one(
            'SELECT id FROM push_tokens WHERE user_id = ? AND token = ?',
            [$userId, $token]
        );
        if ($existing) {
            $this->store->update(
                'push_tokens',
                ['platform' => $platform, 'updated_at' => $now],
                'id = ?',
                [(int) $existing['id']]
            );
            return;
        }
        $this->store->insert('push_tokens', [
            'user_id' => $userId,
            'token' => $token,
            'platform' => $platform,
            'created_at' => $now,
            'updated_at' => $now,
        ]);
        // One device per user in the demo keeps stale tokens from piling up.
        // Keep the 10 most recent tokens only.
        $rows = $this->store->all(
            'SELECT id FROM push_tokens WHERE user_id = ? ORDER BY updated_at DESC',
            [$userId]
        );
        $extra = array_slice(array_column($rows, 'id'), 10);
        foreach ($extra as $id) {
            $this->store->delete('push_tokens', 'id = ?', [(int) $id]);
        }
    }

    public function remove(int $userId, string $token): void
    {
        $this->store->delete('push_tokens', 'user_id = ? AND token = ?', [$userId, $token]);
    }

    /** @return list<string> */
    public function tokensFor(int $userId): array
    {
        $rows = $this->store->all('SELECT token FROM push_tokens WHERE user_id = ?', [$userId]);
        $out = [];
        foreach ($rows as $row) {
            if (!empty($row['token'])) {
                $out[] = (string) $row['token'];
            }
        }
        return array_values(array_unique($out));
    }

    public function removeTokenEverywhere(string $token): void
    {
        $this->store->delete('push_tokens', 'token = ?', [$token]);
    }
}
