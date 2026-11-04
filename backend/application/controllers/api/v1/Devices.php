<?php

declare(strict_types=1);
defined('BASEPATH') or exit('No direct script access allowed');

/**
 * Direktori device + prekey untuk E2EE per device.
 *
 * Endpoint (prefix /devices):
 *   POST register  daftar device + kunci identitas (publik!)
 *   GET  mine       device milik sendiri (+ sisa one-time prekey)
 *   POST revoke     cabut device sendiri
 *   POST prekeys    upload signed + one-time prekey
 *   GET  bundle     ambil bundle handshake 1 device (otpk dikonsumsi)
 *   GET  list       device aktif user lain (diri/teman/satu grup)
 */
class Devices extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['device_model', 'realtime_model']);
    }

    public function register(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['identity_sign_pub', 'identity_dh_pub']);
        $deviceId = trim((string) $this->param('device_id'));
        if ($deviceId === '') {
            $deviceId = 'dev_' . bin2hex(random_bytes(8));
        }
        if (!preg_match('/^[A-Za-z0-9_-]{1,64}$/', $deviceId)) {
            $this->fail('Invalid device_id.', 422, 'invalid_device');
        }
        $signPub = $this->b64key('identity_sign_pub', 32);
        $dhPub = $this->b64key('identity_dh_pub', 32);
        $platform = substr(trim((string) $this->param('platform')), 0, 32);
        $name = substr(trim((string) $this->param('device_name')), 0, 64);
        $device = $this->device_model->register(
            (int) $user['id'],
            $deviceId,
            $platform,
            $name,
            $signPub,
            $dhPub
        );
        $this->ok(['device' => $this->dto($device)], 201);
    }

    public function mine(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $devices = array_map(
            fn (array $row): array => $this->dto($row),
            $this->device_model->mine((int) $user['id'])
        );
        $this->ok(['devices' => $devices]);
    }

    public function revoke(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['device_id']);
        $deviceId = trim((string) $this->param('device_id'));
        if (!$this->device_model->revoke((int) $user['id'], $deviceId)) {
            $this->fail('Device not found.', 404, 'not_found');
        }
        $this->ok(['revoked' => true]);
    }

    public function prekeys(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['device_id']);
        $userId = (int) $user['id'];
        $deviceId = trim((string) $this->param('device_id'));
        if (!$this->device_model->isActive($userId, $deviceId)) {
            $this->fail('Device not found.', 404, 'not_found');
        }
        $signed = null;
        $rawSigned = $this->param('signed_prekey');
        if (is_array($rawSigned)) {
            $keyId = (int) ($rawSigned['key_id'] ?? 0);
            if ($keyId <= 0) {
                $this->fail('Invalid signed_prekey key_id.', 422, 'invalid_prekey');
            }
            $signed = [
                'key_id' => $keyId,
                'public_key' => $this->b64key('signed_prekey.public_key', 32, $rawSigned),
                'signature' => $this->b64key('signed_prekey.signature', 64, $rawSigned),
            ];
        }
        $onetime = [];
        $rawList = $this->param('onetime_prekeys');
        if (is_array($rawList)) {
            foreach (array_slice($rawList, 0, 200) as $item) {
                if (!is_array($item) || (int) ($item['key_id'] ?? 0) <= 0) {
                    $this->fail('Invalid onetime_prekeys entry.', 422, 'invalid_prekey');
                }
                $onetime[] = [
                    'key_id' => (int) $item['key_id'],
                    'public_key' => $this->b64key('onetime_prekeys.public_key', 32, $item),
                ];
            }
        }
        if ($signed === null && !$onetime) {
            $this->fail('Nothing to store.', 422, 'invalid_prekey');
        }
        $added = $this->device_model->storePrekeys($userId, $deviceId, $signed, $onetime);
        $this->ok(['stored_signed' => $signed !== null, 'stored_onetime' => $added], 201);
    }

    public function bundle(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        if (!$this->throttle('bundle:' . client_ip(), 120, 60)) {
            $this->fail('Too many requests.', 429, 'rate_limited');
        }
        $targetId = (int) $this->param('user_id');
        $deviceId = trim((string) $this->param('device_id'));
        if ($targetId <= 0 || $deviceId === '') {
            $this->fail('user_id and device_id required.', 422, 'invalid_request');
        }
        if (!$this->device_model->canSee((int) $user['id'], $targetId)) {
            $this->fail('Device not found.', 404, 'not_found');
        }
        $bundle = $this->device_model->bundle($targetId, $deviceId);
        if (!$bundle) {
            $this->fail('Device not found.', 404, 'not_found');
        }
        $this->ok(['bundle' => $bundle]);
    }

    public function list(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $targetId = (int) $this->param('user_id');
        if ($targetId <= 0) {
            $this->fail('user_id required.', 422, 'invalid_request');
        }
        if (!$this->device_model->canSee((int) $user['id'], $targetId)) {
            $this->fail('User not found.', 404, 'not_found');
        }
        $this->ok(['devices' => $this->device_model->activeDevices($targetId)]);
    }

    /** Base64 strict dengan panjang byte pasti (kunci 32/64 byte). */
    private function b64key(string $field, int $bytes, ?array $from = null): string
    {
        $raw = $from === null ? $this->param($field) : ($from[substr($field, strrpos($field, '.') + 1)] ?? null);
        if (!is_string($raw) || $raw === '') {
            $this->fail("Missing {$field}.", 422, 'invalid_key');
        }
        $decoded = base64_decode($raw, true);
        if ($decoded === false || strlen($decoded) !== $bytes) {
            $this->fail("Invalid {$field}: must be base64 of {$bytes} bytes.", 422, 'invalid_key');
        }
        return $raw;
    }

    private function dto(array $row): array
    {
        return [
            'device_id' => $row['device_id'],
            'platform' => $row['platform'] ?? '',
            'device_name' => $row['device_name'] ?? '',
            'identity_sign_pub' => $row['identity_sign_pub'] ?? '',
            'identity_dh_pub' => $row['identity_dh_pub'] ?? '',
            'created_at' => $row['created_at'] ?? null,
            'last_seen_at' => $row['last_seen_at'] ?? null,
            'revoked_at' => $row['revoked_at'] ?? null,
            'otpk_count' => isset($row['otpk_count']) ? (int) $row['otpk_count'] : null,
        ];
    }
}
