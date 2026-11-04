<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Friends extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['friend_model', 'user_model', 'conversation_model', 'realtime_model']);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $rows = $this->friend_model->friends((int) $user['id']);
        $blockedIds = $this->friend_model->blockedIds((int) $user['id']);
        $friends = [];
        foreach ($rows as $row) {
            $dto = user_dto($row);
            $dto['friendship_id'] = (int) $row['friendship_id'];
            $dto['friends_since'] = $row['friends_since'];
            $dto['blocked_by_me'] = in_array((int) $row['id'], $blockedIds, true);
            $key = min((int) $user['id'], (int) $row['id']) . ':' . max((int) $user['id'], (int) $row['id']);
            $conversation = $this->conversation_model->findDirect($key);
            $dto['conversation_id'] = $conversation ? (int) $conversation['id'] : null;
            $friends[] = $dto;
        }
        $this->ok(['friends' => $friends]);
    }

    public function requests(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $pending = $this->friend_model->pending((int) $user['id']);
        $hidden = array_merge(
            $this->friend_model->blockedIds((int) $user['id']),
            $this->friend_model->blockedByIds((int) $user['id'])
        );

        $map = static function (array $rows) use ($hidden): array {
            $out = [];
            foreach ($rows as $row) {
                if (in_array((int) $row['id'], $hidden, true)) {
                    continue;
                }
                $out[] = [
                    'request_id' => (int) $row['request_id'],
                    'status' => $row['status'],
                    'created_at' => $row['created_at'],
                    'user' => user_dto($row),
                ];
            }
            return $out;
        };

        $this->ok([
            'incoming' => $map($pending['incoming']),
            'outgoing' => $map($pending['outgoing']),
        ]);
    }

    public function send(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['user_id']);

        $targetId = $this->int_param('user_id');
        if ($targetId === (int) $user['id']) {
            $this->fail('You cannot add yourself.', 422, 'invalid_target');
        }
        $target = $this->user_model->find($targetId);
        if (!$target || (int) $target['is_active'] !== 1) {
            $this->fail('User not found.', 404, 'not_found');
        }
        $this->assertNotBlocked((int) $user['id'], $targetId);

        $existing = $this->friend_model->findBetween((int) $user['id'], $targetId);
        if ($existing) {
            if ($existing['status'] === 'accepted') {
                $this->fail('You are already friends.', 409, 'already_friends');
            }
            if ((int) $existing['requester_id'] === (int) $user['id']) {
                $this->ok([
                    'request' => ['request_id' => (int) $existing['id'], 'status' => 'pending'],
                    'user' => user_dto($target),
                ]);
            }
            $this->fail(
                'This user already sent you a friend request. Open your requests to accept it.',
                409,
                'incoming_request',
                ['request_id' => (int) $existing['id'], 'user' => user_dto($target)]
            );
        }

        if (!$this->throttle('friend:' . (int) $user['id'], 40, 3600)) {
            $this->fail('Too many friend requests. Try again later.', 429, 'rate_limited');
        }

        $requestId = $this->friend_model->create((int) $user['id'], $targetId);
        $this->realtime_model->push([$targetId], 'friend_request', [
            'request_id' => $requestId,
            'user' => user_dto($user),
        ]);
        push_notify([$targetId], 'Permintaan pertemanan', ($user['display_name'] ?? 'Seseorang') . ' ingin berteman', [
            'event' => 'friend_request',
            'request_id' => (string) $requestId,
        ]);

        $this->ok([
            'request' => ['request_id' => $requestId, 'status' => 'pending'],
            'user' => user_dto($target),
        ], 201);
    }

    public function respond(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['request_id', 'action']);

        $requestId = $this->int_param('request_id');
        $action = $this->str_param('action');
        if (!in_array($action, ['accept', 'decline'], true)) {
            $this->fail('Action must be accept or decline.', 422, 'validation_error');
        }

        $ok = match ($action) {
            'accept' => $this->friend_model->accept($requestId, (int) $user['id']),
            'decline' => $this->friend_model->decline($requestId, (int) $user['id']),
            default => false,
        };
        if (!$ok) {
            $this->fail('Friend request not found.', 404, 'not_found');
        }

        if ($action === 'accept') {
            $row = $this->store->one('SELECT * FROM friendships WHERE id = ?', [$requestId]);
            $this->realtime_model->push([(int) $row['requester_id']], 'friend_accepted', [
                'user' => user_dto($user),
            ]);
            push_notify([(int) $row['requester_id']], 'Pertemanan diterima', ($user['display_name'] ?? 'Seseorang') . ' menerima pertemananmu', [
                'event' => 'friend_accepted',
            ]);
            $this->ok(['status' => 'accepted']);
        }
        $this->ok(['status' => 'declined']);
    }

    public function remove(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $this->require_fields(['user_id']);
        $targetId = $this->int_param('user_id');
        if ($targetId <= 0) {
            $this->fail('user_id is required.', 422, 'validation_error');
        }
        $this->friend_model->remove((int) $user['id'], $targetId);
        $this->ok(['message' => 'Friend removed.']);
    }

    public function block(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['user_id']);

        $targetId = $this->int_param('user_id');
        if ($targetId === (int) $user['id']) {
            $this->fail('You cannot block yourself.', 422, 'invalid_target');
        }
        $target = $this->user_model->find($targetId);
        if (!$target || (int) $target['is_active'] !== 1) {
            $this->fail('User not found.', 404, 'not_found');
        }
        $this->friend_model->block((int) $user['id'], $targetId);
        $this->ok(['blocked' => true, 'user' => user_dto($target)]);
    }

    public function unblock(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $this->require_fields(['user_id']);

        $targetId = $this->int_param('user_id');
        $this->friend_model->unblock((int) $user['id'], $targetId);
        $this->ok(['blocked' => false]);
    }

    public function blocked(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $ids = $this->friend_model->blockedIds((int) $user['id']);
        $users = [];
        foreach ($ids as $id) {
            $row = $this->user_model->find($id);
            if ($row && (int) $row['is_active'] === 1) {
                $users[] = user_dto($row);
            }
        }
        $this->ok(['users' => $users]);
    }

    /**
     * Blokir dua arah: bila saya memblokir target → 403;
     * bila target memblokir saya → 404 (privasi, seolah tidak ada).
     */
    private function assertNotBlocked(int $userId, int $targetId): void
    {
        if ($this->friend_model->isBlocked($userId, $targetId)) {
            $this->fail('You have blocked this user. Unblock to continue.', 403, 'blocked');
        }
        if ($this->friend_model->isBlocked($targetId, $userId)) {
            $this->fail('User not found.', 404, 'not_found');
        }
    }

    public function status(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $targetId = $this->int_param('user_id');
        $row = $this->friend_model->findBetween((int) $user['id'], $targetId);
        $this->ok([
            'status' => $row ? $row['status'] : 'none',
            'request_id' => $row ? (int) $row['id'] : null,
            'direction' => $row ? ((int) $row['requester_id'] === (int) $user['id'] ? 'outgoing' : 'incoming') : null,
        ]);
    }
}
