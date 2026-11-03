<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Friend_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function findBetween(int $a, int $b): ?array
    {
        return $this->store->one(
            'SELECT * FROM friendships
             WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)
             ORDER BY id DESC LIMIT 1',
            [$a, $b, $b, $a]
        );
    }

    public function areFriends(int $a, int $b): bool
    {
        $row = $this->findBetween($a, $b);
        return $row !== null && $row['status'] === 'accepted';
    }

    public function create(int $from, int $to): int
    {
        $now = now_iso();
        return $this->store->insert('friendships', [
            'requester_id' => $from,
            'addressee_id' => $to,
            'status' => 'pending',
            'created_at' => $now,
            'updated_at' => $now,
        ]);
    }

    public function accept(int $id, int $userId): bool
    {
        $row = $this->store->one('SELECT * FROM friendships WHERE id = ? AND addressee_id = ?', [$id, $userId]);
        if (!$row || $row['status'] !== 'pending') {
            return false;
        }
        $this->store->update('friendships', ['status' => 'accepted', 'updated_at' => now_iso()], 'id = ?', [$id]);
        return true;
    }

    public function decline(int $id, int $userId): bool
    {
        $row = $this->store->one('SELECT * FROM friendships WHERE id = ? AND addressee_id = ?', [$id, $userId]);
        if (!$row || $row['status'] !== 'pending') {
            return false;
        }
        $this->store->delete('friendships', 'id = ?', [$id]);
        return true;
    }

    public function remove(int $a, int $b): void
    {
        $this->store->delete(
            'friendships',
            '((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?))',
            [$a, $b, $b, $a]
        );
    }

    public function friends(int $userId): array
    {
        return $this->store->all(
            'SELECT u.*, f.id AS friendship_id, f.updated_at AS friends_since, f.requester_id
             FROM friendships f
             JOIN users u ON u.id = CASE WHEN f.requester_id = ? THEN f.addressee_id ELSE f.requester_id END
             WHERE (f.requester_id = ? OR f.addressee_id = ?) AND f.status = ? AND u.is_active = 1
             ORDER BY u.display_name ASC',
            [$userId, $userId, $userId, 'accepted']
        );
    }

    public function friendIds(int $userId): array
    {
        $rows = $this->store->all(
            'SELECT CASE WHEN requester_id = ? THEN addressee_id ELSE requester_id END AS friend_id
             FROM friendships
             WHERE (requester_id = ? OR addressee_id = ?) AND status = ?',
            [$userId, $userId, $userId, 'accepted']
        );
        return array_map(
            static fn (int|string $id): int => (int) $id,
            array_column($rows, 'friend_id')
        );
    }

    public function pending(int $userId): array
    {
        $incoming = $this->store->all(
            'SELECT f.id AS request_id, f.status, f.created_at, u.*
             FROM friendships f JOIN users u ON u.id = f.requester_id
             WHERE f.addressee_id = ? AND f.status = ? ORDER BY f.id DESC',
            [$userId, 'pending']
        );
        $outgoing = $this->store->all(
            'SELECT f.id AS request_id, f.status, f.created_at, u.*
             FROM friendships f JOIN users u ON u.id = f.addressee_id
             WHERE f.requester_id = ? AND f.status = ? ORDER BY f.id DESC',
            [$userId, 'pending']
        );
        return ['incoming' => $incoming, 'outgoing' => $outgoing];
    }
}
