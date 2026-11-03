<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class User_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function create(array $data): int
    {
        $now = now_iso();
        $phone = $data['phone'] ?? null;
        return $this->store->insert('users', [
            'uuid' => uuid4(),
            'username' => $data['username'],
            'phone' => $phone,
            'phone_hash' => $phone ? phone_hash($phone) : null,
            'password_hash' => $data['password_hash'],
            'display_name' => $data['display_name'],
            'avatar_url' => $data['avatar_url'] ?? null,
            'bio' => $data['bio'] ?? null,
            'gender' => $data['gender'] ?? null,
            'birthday' => $data['birthday'] ?? null,
            'discoverable' => 1,
            'token_version' => 0,
            'is_active' => 1,
            'created_at' => $now,
            'updated_at' => $now,
        ]);
    }

    public function find(int $id): ?array
    {
        return $this->store->one('SELECT * FROM users WHERE id = ?', [$id]);
    }

    public function findByUsername(string $username): ?array
    {
        return $this->store->one('SELECT * FROM users WHERE username = ?', [$username]);
    }

    public function findByPhone(string $phone): ?array
    {
        return $this->store->one('SELECT * FROM users WHERE phone = ?', [$phone]);
    }

    public function findByLogin(string $login): ?array
    {
        $normalized = normalize_phone($login);
        if ($normalized !== null) {
            $user = $this->findByPhone($normalized);
            if ($user) {
                return $user;
            }
        }
        return $this->findByUsername($login);
    }

    public function usernameExists(string $username): bool
    {
        return (bool) $this->store->value('SELECT 1 FROM users WHERE username = ?', [$username]);
    }

    public function phoneExists(string $phone): bool
    {
        return (bool) $this->store->value('SELECT 1 FROM users WHERE phone = ?', [$phone]);
    }

    public function update(int $id, array $fields): void
    {
        $allowed = ['display_name', 'avatar_url', 'bio', 'gender', 'birthday', 'discoverable', 'phone', 'phone_hash'];
        $data = [];
        foreach ($allowed as $field) {
            if (array_key_exists($field, $fields)) {
                $data[$field] = $fields[$field];
            }
        }
        if (!$data) {
            return;
        }
        $data['updated_at'] = now_iso();
        $this->store->update('users', $data, 'id = ?', [$id]);
    }

    public function touch(int $id, ?float $lat = null, ?float $lng = null): void
    {
        $data = ['last_seen_at' => now_iso()];
        if ($lat !== null && $lng !== null) {
            // Coarse location (about 110 m) is enough for "people nearby".
            $data['latitude'] = round($lat, 3);
            $data['longitude'] = round($lng, 3);
            $data['location_updated_at'] = now_iso();
        }
        $this->store->update('users', $data, 'id = ?', [$id]);
    }

    public function setPassword(int $id, string $hash): void
    {
        $this->store->update(
            'users',
            ['password_hash' => $hash, 'token_version' => (int) $this->store->value('SELECT token_version FROM users WHERE id = ?', [$id]) + 1, 'updated_at' => now_iso()],
            'id = ?',
            [$id]
        );
    }

    public function bumpTokenVersion(int $id): void
    {
        $version = (int) $this->store->value('SELECT token_version FROM users WHERE id = ?', [$id]);
        $this->store->update('users', ['token_version' => $version + 1, 'updated_at' => now_iso()], 'id = ?', [$id]);
    }

    public function search(string $query, int $excludeId, int $limit = 20): array
    {
        $like = '%' . $query . '%';
        return $this->store->all(
            'SELECT * FROM users
             WHERE is_active = 1 AND id <> ? AND (username LIKE ? OR display_name LIKE ?)
             ORDER BY CASE WHEN username = ? THEN 0 ELSE 1 END, display_name ASC
             LIMIT ' . max(1, min(50, $limit)),
            [$excludeId, $like, $like, $query]
        );
    }

    public function nearby(float $lat, float $lng, float $radiusKm, int $excludeId, int $limit = 50): array
    {
        $rows = $this->store->all(
            'SELECT * FROM users
             WHERE is_active = 1 AND discoverable = 1 AND id <> ?
               AND latitude IS NOT NULL AND longitude IS NOT NULL
               AND location_updated_at >= ?
             LIMIT 300',
            [$excludeId, gmdate('Y-m-d\TH:i:s\Z', time() - 7 * 86400)]
        );

        $result = [];
        foreach ($rows as $row) {
            $distance = $this->haversine($lat, $lng, (float) $row['latitude'], (float) $row['longitude']);
            if ($distance <= $radiusKm) {
                $row['distance_km'] = round($distance, 2);
                $result[] = $row;
            }
        }
        usort($result, static fn (array $a, array $b): int => $a['distance_km'] <=> $b['distance_km']);
        return array_slice($result, 0, $limit);
    }

    private function haversine(float $lat1, float $lng1, float $lat2, float $lng2): float
    {
        $earth = 6371.0;
        $dLat = deg2rad($lat2 - $lat1);
        $dLng = deg2rad($lng2 - $lng1);
        $a = sin($dLat / 2) ** 2 + cos(deg2rad($lat1)) * cos(deg2rad($lat2)) * sin($dLng / 2) ** 2;
        return $earth * 2 * atan2(sqrt($a), sqrt(1 - $a));
    }
}
