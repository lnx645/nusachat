<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Kunci publik perangkat (RSA) + kunci sesi percakapan terbungkus.
 * Server menyimpan blob opaque; enkripsi/dekripsi murni client-side.
 */
class Key_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function publishDeviceKey(int $userId, string $deviceId, string $publicKey): void
    {
        $now = now_iso();
        $existing = $this->store->one(
            'SELECT id, public_key FROM device_keys WHERE user_id = ? AND device_id = ?',
            [$userId, $deviceId]
        );
        if ($existing) {
            $changed = trim((string) ($existing['public_key'] ?? '')) !== $publicKey;
            $this->store->update(
                'device_keys',
                ['public_key' => $publicKey, 'updated_at' => $now],
                'id = ?',
                [(int) $existing['id']]
            );
            if ($changed) {
                // Reinstall HP sama: RSA baru = blob lama tak terbuka selamanya.
                // Hapus agar peer tak menunggu holder mati + re-key bisa jalan.
                $this->store->delete(
                    'conversation_keys',
                    'user_id = ? AND device_id = ?',
                    [$userId, $deviceId]
                );
            }
            return;
        }
        $this->store->insert('device_keys', [
            'user_id' => $userId,
            'device_id' => $deviceId,
            'public_key' => $publicKey,
            'created_at' => $now,
            'updated_at' => $now,
        ]);
    }

    /** @return array<int, array{device_id: string, public_key: string, updated_at: string}> */
    public function deviceKeys(int $userId): array
    {
        return $this->store->all(
            'SELECT device_id, public_key, updated_at FROM device_keys WHERE user_id = ? ORDER BY updated_at DESC',
            [$userId]
        );
    }

    public function upsertWrappedKey(int $conversationId, int $userId, string $deviceId, string $wrappedKey): void
    {
        $now = now_iso();
        // Sidik jari pubkey penerima saat ini: blob basi (reinstall) terdeteksi.
        $fp = '';
        $pub = $this->store->value(
            'SELECT public_key FROM device_keys WHERE user_id = ? AND device_id = ?',
            [$userId, $deviceId]
        );
        if (is_string($pub) && base64_decode($pub, true) !== false) {
            $fp = hash('sha256', (string) base64_decode($pub, true));
        }
        $existing = $this->store->one(
            'SELECT id FROM conversation_keys WHERE conversation_id = ? AND user_id = ? AND device_id = ?',
            [$conversationId, $userId, $deviceId]
        );
        if ($existing) {
            $this->store->update(
                'conversation_keys',
                ['wrapped_key' => $wrappedKey, 'key_fp' => $fp, 'updated_at' => $now],
                'id = ?',
                [(int) $existing['id']]
            );
            return;
        }
        $this->store->insert('conversation_keys', [
            'conversation_id' => $conversationId,
            'user_id' => $userId,
            'device_id' => $deviceId,
            'key_fp' => $fp,
            'wrapped_key' => $wrappedKey,
            'created_at' => $now,
            'updated_at' => $now,
        ]);
    }

    /** @return array<int, array{user_id: int, device_id: string, key_fp: string}> */
    public function keyHolders(int $conversationId): array
    {
        return $this->store->all(
            'SELECT user_id, device_id, key_fp FROM conversation_keys WHERE conversation_id = ?',
            [$conversationId]
        );
    }

    public function wrappedKeyFor(int $conversationId, int $userId, string $deviceId = ''): ?string
    {
        // Strict per-device: baris lama (device_id kosong) tidak dipakai untuk
        // device lain — client menemukannya sebagai "belum punya" lalu heal.
        $row = $this->store->value(
            'SELECT wrapped_key FROM conversation_keys WHERE conversation_id = ? AND user_id = ? AND device_id = ?',
            [$conversationId, $userId, $deviceId]
        );
        return $row !== null ? (string) $row : null;
    }

    public function hasAnyWrappedKey(int $conversationId): bool
    {
        return (bool) $this->store->value(
            'SELECT 1 FROM conversation_keys WHERE conversation_id = ? LIMIT 1',
            [$conversationId]
        );
    }
}
