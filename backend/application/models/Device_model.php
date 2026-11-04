<?php

declare(strict_types=1);
defined('BASEPATH') or exit('No direct script access allowed');

/**
 * Direktori device + prekey untuk E2EE per device (docs/E2EE-PROTOCOL.md).
 *
 * Server hanya menyimpan KUNCI PUBLIK + metadata. Tidak ada kunci privat
 * yang pernah transit di sini — bila ada request mengirim privat, tolak.
 */
class Device_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    /** Daftarkan device (baru) atau perbarui kunci (install ulang). */
    public function register(
        int $userId,
        string $deviceId,
        string $platform,
        string $deviceName,
        string $signPub,
        string $dhPub
    ): array {
        $now = now_iso();
        $existing = $this->store->one(
            'SELECT * FROM devices WHERE user_id = ? AND device_id = ?',
            [$userId, $deviceId]
        );
        if ($existing) {
            $this->store->update(
                'devices',
                [
                    'platform' => $platform,
                    'device_name' => $deviceName,
                    'identity_sign_pub' => $signPub,
                    'identity_dh_pub' => $dhPub,
                    'last_seen_at' => $now,
                    'revoked_at' => null,
                ],
                'user_id = ? AND device_id = ?',
                [$userId, $deviceId]
            );
            // Kunci identitas berubah = sesi lama mati: buang semua prekey.
            $this->store->delete(
                'signed_prekeys',
                'user_id = ? AND device_id = ?',
                [$userId, $deviceId]
            );
            $this->store->delete(
                'onetime_prekeys',
                'user_id = ? AND device_id = ?',
                [$userId, $deviceId]
            );
            return $this->store->one(
                'SELECT * FROM devices WHERE user_id = ? AND device_id = ?',
                [$userId, $deviceId]
            );
        }
        $id = $this->store->insert('devices', [
            'user_id' => $userId,
            'device_id' => $deviceId,
            'platform' => $platform,
            'device_name' => $deviceName,
            'identity_sign_pub' => $signPub,
            'identity_dh_pub' => $dhPub,
            'created_at' => $now,
            'last_seen_at' => $now,
            'revoked_at' => null,
        ]);
        return $this->store->one('SELECT * FROM devices WHERE id = ?', [$id]);
    }

    /** Semua device milik user (termasuk yang di-revoke, untuk UI kelola). */
    public function mine(int $userId): array
    {
        return $this->store->all(
            'SELECT device_id, platform, device_name, identity_sign_pub, identity_dh_pub,
                    created_at, last_seen_at, revoked_at,
                    (SELECT COUNT(*) FROM onetime_prekeys o
                     WHERE o.user_id = d.user_id AND o.device_id = d.device_id) AS otpk_count
             FROM devices d WHERE user_id = ? ORDER BY created_at ASC',
            [$userId]
        );
    }

    public function revoke(int $userId, string $deviceId): bool
    {
        $ok = $this->store->update(
            'devices',
            ['revoked_at' => now_iso()],
            'user_id = ? AND device_id = ? AND revoked_at IS NULL',
            [$userId, $deviceId]
        ) > 0;
        if ($ok) {
            $this->store->delete(
                'signed_prekeys',
                'user_id = ? AND device_id = ?',
                [$userId, $deviceId]
            );
            $this->store->delete(
                'onetime_prekeys',
                'user_id = ? AND device_id = ?',
                [$userId, $deviceId]
            );
        }
        return $ok;
    }

    public function isActive(int $userId, string $deviceId): bool
    {
        $row = $this->store->one(
            'SELECT revoked_at FROM devices WHERE user_id = ? AND device_id = ?',
            [$userId, $deviceId]
        );
        return $row !== null && $row['revoked_at'] === null;
    }

    /** Upload/rotasi prekey. Signed hanya simpan 2 terbaru (in-flight). */
    public function storePrekeys(
        int $userId,
        string $deviceId,
        ?array $signed,
        array $onetime
    ): int {
        $now = now_iso();
        if ($signed !== null) {
            $this->store->run(
                'DELETE FROM signed_prekeys WHERE user_id = ? AND device_id = ?',
                [$userId, $deviceId]
            );
            $this->store->insert('signed_prekeys', [
                'user_id' => $userId,
                'device_id' => $deviceId,
                'key_id' => $signed['key_id'],
                'public_key' => $signed['public_key'],
                'signature' => $signed['signature'],
                'created_at' => $now,
            ]);
        }
        $added = 0;
        foreach (array_slice($onetime, 0, 200) as $item) {
            try {
                $this->store->insert('onetime_prekeys', [
                    'user_id' => $userId,
                    'device_id' => $deviceId,
                    'key_id' => $item['key_id'],
                    'public_key' => $item['public_key'],
                    'created_at' => $now,
                ]);
                ++$added;
            } catch (Throwable) {
                // key_id duplikat (retry upload) — lewati, bukan error.
            }
        }
        $this->store->update(
            'devices',
            ['last_seen_at' => $now],
            'user_id = ? AND device_id = ?',
            [$userId, $deviceId]
        );
        return $added;
    }

    /**
     * Ambil bundle handshake untuk SATU device target. One-time prekey
     * (bila ada) langsung DIHAPUS saat diserahkan — tidak pernah dipakai
     * dua kali. Return null bila device tidak aktif.
     */
    public function bundle(int $userId, string $deviceId): ?array
    {
        $device = $this->store->one(
            'SELECT device_id, identity_sign_pub, identity_dh_pub
             FROM devices WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL',
            [$userId, $deviceId]
        );
        if (!$device) {
            return null;
        }
        $signed = $this->store->one(
            'SELECT key_id, public_key, signature FROM signed_prekeys
             WHERE user_id = ? AND device_id = ? ORDER BY id DESC LIMIT 1',
            [$userId, $deviceId]
        );
        $one = $this->store->one(
            'SELECT id, key_id, public_key FROM onetime_prekeys
             WHERE user_id = ? AND device_id = ? ORDER BY id ASC LIMIT 1',
            [$userId, $deviceId]
        );
        $otpk = null;
        if ($one) {
            $this->store->delete('onetime_prekeys', 'id = ?', [(int) $one['id']]);
            $otpk = ['key_id' => (int) $one['key_id'], 'public_key' => $one['public_key']];
        }
        return [
            'device_id' => $device['device_id'],
            'identity_sign_pub' => $device['identity_sign_pub'],
            'identity_dh_pub' => $device['identity_dh_pub'],
            'signed_prekey' => $signed ? [
                'key_id' => (int) $signed['key_id'],
                'public_key' => $signed['public_key'],
                'signature' => $signed['signature'],
            ] : null,
            'onetime_prekey' => $otpk,
        ];
    }

    /** Device aktif milik user (untuk fan-out + distribusi sender key). */
    public function activeDevices(int $userId): array
    {
        return $this->store->all(
            'SELECT device_id, platform, device_name, identity_sign_pub, identity_dh_pub
             FROM devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at ASC',
            [$userId]
        );
    }

    /** Boleh melihat device user lain? diri/teman/satu percakapan. */
    public function canSee(int $viewerId, int $targetId): bool
    {
        if ($viewerId === $targetId) {
            return true;
        }
        $friend = $this->store->one(
            "SELECT 1 FROM friendships
             WHERE status = 'accepted' AND ((requester_id = ? AND addressee_id = ?)
                OR (requester_id = ? AND addressee_id = ?))",
            [$viewerId, $targetId, $targetId, $viewerId]
        );
        if ($friend) {
            return true;
        }
        $shared = $this->store->one(
            'SELECT 1 FROM conversation_members a
             JOIN conversation_members b ON b.conversation_id = a.conversation_id
             WHERE a.user_id = ? AND b.user_id = ? LIMIT 1',
            [$viewerId, $targetId]
        );
        return $shared !== null;
    }
}
