<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Realtime_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function push(array $userIds, string $type, array $data): void
    {
        $ids = array_values(array_unique(array_filter(
            array_map(static fn (int|string $id): int => (int) $id, $userIds)
        )));
        if (!$ids) {
            return;
        }
        $payload = json_encode(['type' => $type, 'data' => $data], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $now = now_iso();
        $events = [];
        foreach ($ids as $userId) {
            $id = $this->store->insert('realtime_events', [
                'user_id' => $userId,
                'type' => $type,
                'payload' => $payload,
                'created_at' => $now,
            ]);
            $events[] = ['id' => $id, 'user_id' => $userId, 'payload' => $payload, 'created_at' => $now];
        }
        realtime_notify($events);
    }

    public function since(int $lastId, int $limit = 200): array
    {
        return $this->store->all(
            'SELECT * FROM realtime_events WHERE id > ? ORDER BY id ASC LIMIT ' . max(1, min(500, $limit)),
            [$lastId]
        );
    }

    public function maxId(): int
    {
        return (int) $this->store->value('SELECT COALESCE(MAX(id), 0) FROM realtime_events');
    }

    public function prune(int $days = 1): void
    {
        $this->store->delete('realtime_events', 'created_at < ?', [gmdate('Y-m-d\TH:i:s\Z', time() - $days * 86400)]);
    }
}
