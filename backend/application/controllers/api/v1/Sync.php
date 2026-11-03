<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Sync extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['conversation_model', 'friend_model', 'moment_model']);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $userId = (int) $user['id'];

        $conversations = $this->conversation_model->listFor($userId, 200);
        $unread = 0;
        foreach ($conversations as $row) {
            $unread += (int) $row['unread_count'];
        }
        $pending = $this->friend_model->pending($userId);
        $friendIds = $this->friend_model->friendIds($userId);

        $since = $this->str_param('since');
        if ($since === '') {
            $since = gmdate('Y-m-d\TH:i:s\Z', time() - 7 * 86400);
        }
        $newMoments = 0;
        if ($friendIds) {
            $placeholders = implode(',', array_fill(0, count($friendIds), '?'));
            $newMoments = (int) $this->store->value(
                'SELECT COUNT(*) FROM moments WHERE deleted_at IS NULL AND user_id IN (' . $placeholders . ') AND created_at > ?',
                array_merge($friendIds, [$since])
            );
        }

        $this->ok([
            'server_time' => now_iso(),
            'unread_total' => $unread,
            'conversation_count' => count($conversations),
            'pending_friend_requests' => count($pending['incoming']),
            'friend_count' => count($friendIds),
            'new_moments_since' => $newMoments,
            'ws_url' => $this->app['ws_url'],
        ]);
    }
}
