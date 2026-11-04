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

    /**
     * Relasi terbaru viewer vs banyak peer dalam 1 query (cermin findBetween:
     * baris id terbesar per pasangan).
     *
     * @param int[] $peerIds
     * @return array<int, array> peer_id => baris friendships
     */
    public function findBetweenMany(int $viewerId, array $peerIds): array
    {
        $peerIds = array_values(array_unique(array_filter(
            array_map('intval', $peerIds),
            static fn (int $id): bool => $id > 0 && $id !== $viewerId
        )));
        if (!$peerIds) {
            return [];
        }
        $placeholders = implode(',', array_fill(0, count($peerIds), '?'));
        $rows = $this->store->all(
            'SELECT * FROM friendships
             WHERE (requester_id = ? AND addressee_id IN (' . $placeholders . '))
                OR (addressee_id = ? AND requester_id IN (' . $placeholders . '))
             ORDER BY id DESC',
            array_merge([$viewerId], $peerIds, [$viewerId], $peerIds)
        );
        $out = [];
        foreach ($rows as $row) {
            $peer = (int) $row['requester_id'] === $viewerId
                ? (int) $row['addressee_id']
                : (int) $row['requester_id'];
            // ORDER BY id DESC: yang pertama ditemui = terbaru (semantik findBetween).
            if (!isset($out[$peer])) {
                $out[$peer] = $row;
            }
        }
        return $out;
    }

    public function create(int $from, int $to, ?string $message = null): int
    {
        $now = now_iso();
        return $this->store->insert('friendships', [
            'requester_id' => $from,
            'addressee_id' => $to,
            'status' => 'pending',
            'message' => $message,
            'created_at' => $now,
            'updated_at' => $now,
        ]);
    }

    /**
     * Nama panggilan pribadi untuk seorang teman (hanya terlihat olehku).
     */
    public function setRemark(int $userId, int $friendId, ?string $remark): void
    {
        if ($remark === null || trim($remark) === '') {
            $this->store->delete('friend_remarks', 'user_id = ? AND friend_id = ?', [$userId, $friendId]);
            return;
        }
        $existing = $this->store->one(
            'SELECT id FROM friend_remarks WHERE user_id = ? AND friend_id = ?',
            [$userId, $friendId]
        );
        if ($existing) {
            $this->store->update(
                'friend_remarks',
                ['remark' => mb_substr(trim($remark), 0, 40), 'updated_at' => now_iso()],
                'id = ?',
                [(int) $existing['id']]
            );
            return;
        }
        $this->store->insert('friend_remarks', [
            'user_id' => $userId,
            'friend_id' => $friendId,
            'remark' => mb_substr(trim($remark), 0, 40),
            'updated_at' => now_iso(),
        ]);
    }

    public function remark(int $userId, int $friendId): ?string
    {
        $remark = $this->store->value(
            'SELECT remark FROM friend_remarks WHERE user_id = ? AND friend_id = ?',
            [$userId, $friendId]
        );
        return is_string($remark) && $remark !== '' ? $remark : null;
    }

    /** @return array<int, string> friend_id => remark */
    public function remarksFor(int $userId): array
    {
        $rows = $this->store->all('SELECT friend_id, remark FROM friend_remarks WHERE user_id = ?', [$userId]);
        $out = [];
        foreach ($rows as $row) {
            $out[(int) $row['friend_id']] = (string) $row['remark'];
        }
        return $out;
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

    public function block(int $userId, int $targetId): void
    {
        $existing = $this->store->one(
            'SELECT id FROM blocks WHERE user_id = ? AND blocked_user_id = ?',
            [$userId, $targetId]
        );
        if (!$existing) {
            $this->store->insert('blocks', [
                'user_id' => $userId,
                'blocked_user_id' => $targetId,
                'created_at' => now_iso(),
            ]);
        }
    }

    public function unblock(int $userId, int $targetId): void
    {
        $this->store->delete(
            'blocks',
            'user_id = ? AND blocked_user_id = ?',
            [$userId, $targetId]
        );
    }

    public function isBlocked(int $userId, int $targetId): bool
    {
        return (bool) $this->store->value(
            'SELECT 1 FROM blocks WHERE user_id = ? AND blocked_user_id = ?',
            [$userId, $targetId]
        );
    }

    /**
     * @return int[] id yang diblokir oleh user (satu arah)
     */
    public function blockedIds(int $userId): array
    {
        $rows = $this->store->all('SELECT blocked_user_id FROM blocks WHERE user_id = ?', [$userId]);
        return array_map(static fn ($id): int => (int) $id, array_column($rows, 'blocked_user_id'));
    }

    /**
     * @return int[] id yang memblokir user (satu arah)
     */
    public function blockedByIds(int $userId): array
    {
        $rows = $this->store->all('SELECT user_id FROM blocks WHERE blocked_user_id = ?', [$userId]);
        return array_map(static fn ($id): int => (int) $id, array_column($rows, 'user_id'));
    }

    public function pending(int $userId): array
    {
        $incoming = $this->store->all(
            'SELECT f.id AS request_id, f.status, f.message AS greeting, f.created_at, u.*
             FROM friendships f JOIN users u ON u.id = f.requester_id
             WHERE f.addressee_id = ? AND f.status = ? ORDER BY f.id DESC',
            [$userId, 'pending']
        );
        $outgoing = $this->store->all(
            'SELECT f.id AS request_id, f.status, f.message AS greeting, f.created_at, u.*
             FROM friendships f JOIN users u ON u.id = f.addressee_id
             WHERE f.requester_id = ? AND f.status = ? ORDER BY f.id DESC',
            [$userId, 'pending']
        );
        return ['incoming' => $incoming, 'outgoing' => $outgoing];
    }
}
