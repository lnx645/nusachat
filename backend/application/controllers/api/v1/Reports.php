<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Pelaporan konten: pengguna, pesan, momen, komentar, atau story.
 * Laporan masuk antrean 'pending' untuk ditinjau admin.
 */
class Reports extends MY_Controller
{
    private const TARGETS = ['user', 'message', 'moment', 'comment', 'story'];

    private const REASONS = ['spam', 'abuse', 'fraud', 'porn', 'violence', 'other'];

    public function __construct()
    {
        parent::__construct();
        $this->load->model(['user_model']);
    }

    public function create(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['target_type', 'target_id', 'reason']);

        $type = strtolower(trim($this->str_param('target_type')));
        if (!in_array($type, self::TARGETS, true)) {
            $this->fail('target_type must be one of: ' . implode(', ', self::TARGETS) . '.', 422, 'invalid_target');
        }
        $targetId = $this->int_param('target_id');
        if ($targetId <= 0) {
            $this->fail('target_id is required.', 422, 'validation_error');
        }
        $reason = strtolower(trim($this->str_param('reason')));
        if (!in_array($reason, self::REASONS, true)) {
            $this->fail('reason must be one of: ' . implode(', ', self::REASONS) . '.', 422, 'invalid_reason');
        }
        $details = trim($this->str_param('details'));
        if (mb_strlen($details) > 500) {
            $this->fail('details is limited to 500 characters.', 422, 'details_too_long');
        }

        if (!$this->targetExists($type, $targetId, (int) $user['id'])) {
            $this->fail('Reported content was not found.', 404, 'not_found');
        }
        if (!$this->throttle('report:' . (int) $user['id'], 20, 3600)) {
            $this->fail('Too many reports. Try again later.', 429, 'rate_limited');
        }

        $id = $this->store->insert('reports', [
            'reporter_id' => (int) $user['id'],
            'target_type' => $type,
            'target_id' => $targetId,
            'reason' => $reason,
            'details' => $details !== '' ? $details : null,
            'status' => 'pending',
            'created_at' => now_iso(),
        ]);
        $this->ok(['report' => [
            'id' => $id,
            'target_type' => $type,
            'target_id' => $targetId,
            'reason' => $reason,
            'status' => 'pending',
        ]], 201);
    }

    public function mine(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $rows = $this->store->all(
            'SELECT * FROM reports WHERE reporter_id = ? ORDER BY id DESC LIMIT 50',
            [(int) $user['id']]
        );
        $this->ok(['reports' => array_map(
            static fn (array $r): array => [
                'id' => (int) $r['id'],
                'target_type' => $r['target_type'],
                'target_id' => (int) $r['target_id'],
                'reason' => $r['reason'],
                'details' => $r['details'],
                'status' => $r['status'],
                'created_at' => $r['created_at'],
            ],
            $rows
        )]);
    }

    private function targetExists(string $type, int $targetId, int $reporterId): bool
    {
        return match ($type) {
            'user' => $targetId !== $reporterId
                && (bool) $this->store->value('SELECT 1 FROM users WHERE id = ? AND is_active = 1', [$targetId]),
            'message' => (bool) $this->store->value('SELECT 1 FROM messages WHERE id = ?', [$targetId]),
            'moment' => (bool) $this->store->value('SELECT 1 FROM moments WHERE id = ? AND deleted_at IS NULL', [$targetId]),
            'comment' => (bool) $this->store->value('SELECT 1 FROM moment_comments WHERE id = ?', [$targetId]),
            'story' => (bool) $this->store->value('SELECT 1 FROM stories WHERE id = ? AND deleted_at IS NULL', [$targetId]),
            default => false,
        };
    }
}
