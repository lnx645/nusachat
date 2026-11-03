<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Conversations extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model([
            'conversation_model', 'message_model', 'friend_model',
            'user_model', 'realtime_model',
        ]);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $rows = $this->conversation_model->listFor((int) $user['id']);
        $ids = array_map('intval', array_column($rows, 'id'));
        $latest = $this->message_model->latestForConversations($ids);

        $conversations = [];
        foreach ($rows as $row) {
            $lastRow = $latest[(int) $row['id']] ?? null;
            $row['last_message'] = $lastRow ? message_dto($lastRow) : null;
            $conversations[] = $this->present($row, (int) $user['id']);
        }
        $this->ok(['conversations' => $conversations]);
    }

    public function show(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        if (!$row) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $this->ok(['conversation' => $this->present($row, (int) $user['id'], true)]);
    }

    public function create_direct(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['user_id']);
        $targetId = $this->int_param('user_id');

        if ($targetId === (int) $user['id']) {
            $this->fail('You cannot chat with yourself.', 422, 'invalid_target');
        }
        $target = $this->user_model->find($targetId);
        if (!$target || (int) $target['is_active'] !== 1) {
            $this->fail('User not found.', 404, 'not_found');
        }
        if (!$this->friend_model->areFriends((int) $user['id'], $targetId)) {
            $this->fail('Add this user as a friend before starting a chat.', 403, 'not_friends');
        }

        $key = min((int) $user['id'], $targetId) . ':' . max((int) $user['id'], $targetId);
        $existing = $this->conversation_model->findDirect($key);
        if ($existing) {
            $row = $this->conversation_model->forUser((int) $existing['id'], (int) $user['id']);
            $this->ok(['conversation' => $this->present($row, (int) $user['id'], true)]);
        }

        $id = $this->conversation_model->createDirect((int) $user['id'], $targetId);
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        $this->ok(['conversation' => $this->present($row, (int) $user['id'], true)], 201);
    }

    public function create_group(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['title', 'member_ids']);

        $title = $this->str_param('title');
        if (mb_strlen($title) < 2 || mb_strlen($title) > 60) {
            $this->fail('Group name must be 2-60 characters.', 422, 'invalid_title');
        }
        $memberIds = $this->param('member_ids');
        if (!is_array($memberIds) || !$memberIds) {
            $this->fail('member_ids must be a non-empty array.', 422, 'validation_error');
        }
        $memberIds = array_values(array_unique(array_map('intval', $memberIds)));
        if (count($memberIds) > 49) {
            $this->fail('A group can have at most 50 members.', 422, 'too_many_members');
        }
        foreach ($memberIds as $memberId) {
            $member = $this->user_model->find($memberId);
            if (!$member || (int) $member['is_active'] !== 1) {
                $this->fail('One of the selected users no longer exists.', 422, 'invalid_member');
            }
            if ($memberId !== (int) $user['id'] && !$this->friend_model->areFriends((int) $user['id'], $memberId)) {
                $this->fail('You can only add friends to a group.', 403, 'not_friends');
            }
        }

        $id = $this->conversation_model->createGroup((int) $user['id'], $title, $memberIds);
        $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " membuat grup \"" . $title . "\"");
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        $this->ok(['conversation' => $this->present($row, (int) $user['id'], true)], 201);
    }

    public function messages(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        if (!$row) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }

        $beforeId = $this->param('before_id') !== null ? $this->int_param('before_id') : null;
        $afterId = $this->param('after_id') !== null ? $this->int_param('after_id') : null;
        $limit = $this->int_param('limit', 30);
        $rows = $this->message_model->list($id, $beforeId, $afterId, $limit);
        // Disappearing messages: hide pesan lama melebihi batas (kecuali system)
        if (!empty($row['disappearing_days']) && (int) $row['disappearing_days'] > 0) {
            $cutoff = time() - (int) $row['disappearing_days'] * 86400;
            $rows = array_values(array_filter($rows, function ($m) use ($cutoff) {
                if (($m['type'] ?? '') === 'system') return true;
                $ts = strtotime($m['created_at'] ?? 'now');
                return $ts === false || $ts >= $cutoff;
            }));
        }

        $this->message_model->markDelivered($id, (int) $user['id']);

        $messages = array_map('message_dto', $rows);
        $this->ok([
            'messages' => $messages,
            'has_more' => count($rows) >= max(1, min(100, $limit)),
            'conversation_id' => $id,
        ]);
    }

    public function send(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'type']);

        $id = $this->int_param('id');
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        if (!$row) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }

        // Group send permission
        if ($row['type'] === 'group' && !empty($row['only_admins_can_send']) && (int) $row['created_by'] !== (int) $user['id']) {
            $role = $this->store->value('SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?', [$id, (int) $user['id']]);
            if ($role !== 'owner' && $role !== 'admin') {
                $this->fail('Only admins can send messages in this group.', 403, 'admin_only');
            }
        }

        $type = $this->str_param('type');
        $allowed = ['text', 'image', 'file', 'audio', 'video', 'location'];
        if (!in_array($type, $allowed, true)) {
            $this->fail('Unsupported message type.', 422, 'invalid_type');
        }

        $body = $this->str_param('body');
        $attachmentUrl = $this->str_param('attachment_url');
        $attachmentMeta = is_array($this->param('attachment_meta')) ? $this->param('attachment_meta') : null;
        if ($type === 'text' && $body === '') {
            $this->fail('Message cannot be empty.', 422, 'empty_message');
        }
        if ($type === 'location') {
            $lat = $attachmentMeta['lat'] ?? null;
            $lng = $attachmentMeta['lng'] ?? null;
            if (!is_numeric($lat) || !is_numeric($lng) || (float) $lat < -90 || (float) $lat > 90 || (float) $lng < -180 || (float) $lng > 180) {
                $this->fail('Location requires lat and lng.', 422, 'invalid_location');
            }
            $attachmentMeta['lat'] = (float) $lat;
            $attachmentMeta['lng'] = (float) $lng;
        } elseif ($type !== 'text' && $attachmentUrl === '') {
            $this->fail('attachment_url is required for this message type.', 422, 'missing_attachment');
        }
        if (mb_strlen($body) > 4000) {
            $this->fail('Message is too long (max 4000 characters).', 422, 'message_too_long');
        }

        $replyTo = $this->param('reply_to_id') !== null ? $this->int_param('reply_to_id') : null;
        if ($replyTo) {
            $reply = $this->message_model->find($replyTo);
            if (!$reply || (int) $reply['conversation_id'] !== $id) {
                $this->fail('The message you replied to was not found.', 422, 'invalid_reply');
            }
        }

        $clientId = $this->str_param('client_id');
        if ($clientId !== '' && mb_strlen($clientId) > 80) {
            $this->fail('client_id is too long.', 422, 'invalid_client_id');
        }

        $result = $this->message_model->create([
            'conversation_id' => $id,
            'sender_id' => (int) $user['id'],
            'type' => $type,
            'body' => $body !== '' ? $body : null,
            'attachment_url' => $attachmentUrl !== '' ? $attachmentUrl : null,
            'attachment_meta' => $attachmentMeta,
            'reply_to_id' => $replyTo,
            'client_id' => $clientId !== '' ? $clientId : null,
        ]);

        $message = message_dto($result['row']);
        if (!$result['duplicate']) {
            $this->conversation_model->setLastMessage($id, (int) $result['row']['id'], $result['row']['created_at']);
            $memberIds = $this->conversation_model->memberIds($id);
            $this->realtime_model->push($memberIds, 'message', [
                'conversation_id' => $id,
                'message' => $message,
            ]);
            $preview = $body !== '' ? mb_substr($body, 0, 120) : match ($type) {
                'image' => 'Mengirim foto',
                'video' => 'Mengirim video',
                'audio' => 'Mengirim pesan suara',
                'location' => 'Berbagi lokasi',
                default => 'Mengirim lampiran',
            };
            $targets = array_values(array_diff($memberIds, [(int) $user['id']]));
            if ($targets) {
                $unmuted = $this->store->all(
                    'SELECT user_id FROM conversation_members WHERE conversation_id = ? AND user_id IN (' .
                    implode(',', array_fill(0, count($targets), '?')) . ') AND (muted IS NULL OR muted = 0)',
                    array_merge([$id], $targets)
                );
                $unmutedIds = array_map(static fn (array $r): int => (int) $r['user_id'], $unmuted);
                if ($unmutedIds) {
                    push_notify($unmutedIds, (string) ($user['display_name'] ?? 'Pesan baru'), $preview, [
                        'event' => 'message',
                        'conversation_id' => (string) $id,
                        'message_id' => (string) $result['row']['id'],
                    ]);
                }
            }
        }

        $this->ok(['message' => $message, 'duplicate' => $result['duplicate']], $result['duplicate'] ? 200 : 201);
    }

    public function read(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'message_id']);

        $id = $this->int_param('id');
        $messageId = $this->int_param('message_id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $message = $this->message_model->find($messageId);
        if (!$message || (int) $message['conversation_id'] !== $id) {
            $this->fail('Message not found.', 404, 'not_found');
        }

        $this->conversation_model->markRead($id, (int) $user['id'], $messageId);
        $this->message_model->markReadUpTo($id, (int) $user['id'], $messageId);

        $others = array_values(array_diff($this->conversation_model->memberIds($id), [(int) $user['id']]));
        $this->realtime_model->push($others, 'read', [
            'conversation_id' => $id,
            'user_id' => (int) $user['id'],
            'message_id' => $messageId,
        ]);
        $this->ok(['message_id' => $messageId]);
    }

    public function typing(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $others = array_values(array_diff($this->conversation_model->memberIds($id), [(int) $user['id']]));
        $this->realtime_model->push($others, 'typing', [
            'conversation_id' => $id,
            'user_id' => (int) $user['id'],
            'display_name' => $user['display_name'],
        ]);
        $this->ok(['ok' => true]);
    }

    public function add_members(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'user_ids']);

        $id = $this->int_param('id');
        $conversation = $this->conversation_model->find($id);
        if (!$conversation || $conversation['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        $isOwner = (int) $conversation['created_by'] === (int) $user['id'];
        $role = $this->store->value('SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?', [$id, (int) $user['id']]);
        $isAdmin = $isOwner || $role === 'owner' || $role === 'admin';
        if (!empty($conversation['only_admins_can_add'])) {
            if (!$isAdmin) {
                $this->fail('Only admins can add members.', 403, 'admin_only');
            }
        } else {
            // When open, any member can add
            if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
                $this->fail('You are not a member.', 403, 'forbidden');
            }
        }
        $userIds = $this->param('user_ids');
        if (!is_array($userIds) || !$userIds) {
            $this->fail('user_ids must be a non-empty array.', 422, 'validation_error');
        }
        $valid = [];
        foreach (array_unique(array_map('intval', $userIds)) as $userId) {
            $member = $this->user_model->find($userId);
            if ($member && (int) $member['is_active'] === 1) {
                $valid[] = $userId;
            }
        }
        $this->conversation_model->addMembers($id, $valid);
        if ($valid) {
            $names = [];
            foreach ($valid as $uid) {
                $u = $this->user_model->find($uid);
                if ($u) $names[] = $u['display_name'];
            }
            $count = count($names);
            if ($count === 1) {
                $label = $names[0];
            } elseif ($count === 2) {
                $label = $names[0] . " dan " . $names[1];
            } elseif ($count === 3) {
                $label = $names[0] . ", " . $names[1] . " dan " . $names[2];
            } else {
                $label = $names[0] . ", " . $names[1] . " dan " . ($count - 2) . " lainnya";
            }
            $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " menambahkan " . $label);
        }
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', [
            'conversation_id' => $id,
        ]);
        $this->ok(['members' => array_map(
            static fn (array $row): array => user_dto($row),
            $this->conversation_model->members($id)
        )]);
    }

    public function remove_member(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'user_id']);

        $id = $this->int_param('id');
        $targetId = $this->int_param('user_id');
        $conversation = $this->conversation_model->find($id);
        if (!$conversation || $conversation['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        $isOwner = (int) $conversation['created_by'] === (int) $user['id'];
        if (!$isOwner && $targetId !== (int) $user['id']) {
            $this->fail('You can only leave the group yourself.', 403, 'forbidden');
        }
        if (!$this->conversation_model->isMember($id, $targetId)) {
            $this->fail('This user is not a member of the group.', 404, 'not_found');
        }

        $membersBefore = $this->conversation_model->members($id);
        $targetUser = $this->user_model->find($targetId);
        $targetName = $targetUser['display_name'] ?? ("#" . $targetId);
        $this->conversation_model->removeMember($id, $targetId);

        if ((int) $targetId === (int) $user['id']) {
            $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " keluar");
        } else {
            $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " mengeluarkan " . $targetName);
        }

        if ($isOwner && $targetId === (int) $user['id']) {
            $remaining = array_values(array_filter(
                $membersBefore,
                static fn (array $row): bool => (int) $row['id'] !== $targetId
            ));
            if ($remaining) {
                $nextOwner = $remaining[0];
                $this->store->update('conversations', ['created_by' => (int) $nextOwner['id']], 'id = ?', [$id]);
                $this->store->update('conversation_members', ['role' => 'owner'], 'conversation_id = ? AND user_id = ?', [$id, (int) $nextOwner['id']]);
            }
        }

        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', [
            'conversation_id' => $id,
            'removed_user_id' => $targetId,
        ]);
        $this->ok(['message' => 'Member removed.']);
    }

    public function mute(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $muted = $this->bool_param('muted') ? 1 : 0;
        $this->store->update(
            'conversation_members',
            ['muted' => $muted],
            'conversation_id = ? AND user_id = ?',
            [$id, (int) $user['id']]
        );
        $this->ok(['muted' => (bool) $muted]);
    }

    public function group_settings(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        if (!$row || $row['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        $this->ok(['settings' => [
            'only_admins_can_send' => (bool) ($row['only_admins_can_send'] ?? false),
            'only_admins_can_edit' => (bool) ($row['only_admins_can_edit'] ?? false),
            'only_admins_can_add' => (bool) ($row['only_admins_can_add'] ?? false),
            'invite_link_enabled' => (bool) ($row['invite_link_enabled'] ?? true),
            'approve_new_members' => (bool) ($row['approve_new_members'] ?? false),
            'disappearing_days' => (int) ($row['disappearing_days'] ?? 0),
        ]]);
    }

    public function update_group(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        $row = $this->conversation_model->find($id);
        if (!$row || $row['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        $isOwner = (int) $row['created_by'] === (int) $user['id'];
        $role = $this->store->value('SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?', [$id, (int) $user['id']]);
        $isAdmin = $isOwner || $role === 'owner' || $role === 'admin';
        if (!empty($row['only_admins_can_edit']) && !$isAdmin) {
            $this->fail('Only admins can edit group info.', 403, 'admin_only');
        }
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('You are not a member.', 403, 'forbidden');
        }
        $fields = [];
        if ($this->param('title') !== null) {
            $title = trim($this->str_param('title'));
            if (mb_strlen($title) < 2 || mb_strlen($title) > 60) {
                $this->fail('Group name must be 2-60 characters.', 422, 'invalid_title');
            }
            $fields['title'] = $title;
        }
        if ($this->param('avatar_url') !== null) {
            $fields['avatar_url'] = trim($this->str_param('avatar_url')) ?: null;
        }
        if (!$fields) {
            $this->fail('No fields to update.', 422, 'validation_error');
        }
        $fields['updated_at'] = now_iso();
        $this->store->update('conversations', $fields, 'id = ?', [$id]);
        $updated = $this->conversation_model->forUser($id, (int) $user['id']);
        $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " mengubah info grup");
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', ['conversation_id' => $id]);
        $this->ok(['conversation' => $this->present($updated, (int) $user['id'], true)]);
    }

    public function update_group_settings(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        $row = $this->conversation_model->find($id);
        if (!$row || $row['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        $isOwner = (int) $row['created_by'] === (int) $user['id'];
        $role = $this->store->value('SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?', [$id, (int) $user['id']]);
        $isAdmin = $isOwner || $role === 'owner' || $role === 'admin';
        if (!$isAdmin) {
            $this->fail('Only admins can change group settings.', 403, 'admin_only');
        }

        $fields = [];
        if ($this->param('only_admins_can_send') !== null) {
            $fields['only_admins_can_send'] = $this->bool_param('only_admins_can_send') ? 1 : 0;
        }
        if ($this->param('only_admins_can_edit') !== null) {
            $fields['only_admins_can_edit'] = $this->bool_param('only_admins_can_edit') ? 1 : 0;
        }
        if ($this->param('only_admins_can_add') !== null) {
            $fields['only_admins_can_add'] = $this->bool_param('only_admins_can_add') ? 1 : 0;
        }
        if ($this->param('invite_link_enabled') !== null) {
            $fields['invite_link_enabled'] = $this->bool_param('invite_link_enabled') ? 1 : 0;
        }
        if ($this->param('approve_new_members') !== null) {
            $fields['approve_new_members'] = $this->bool_param('approve_new_members') ? 1 : 0;
        }
        if ($this->param('disappearing_days') !== null) {
            $days = (int) $this->int_param('disappearing_days', 0);
            $allowed = [0, 1, 7, 90];
            if (!in_array($days, $allowed, true)) {
                $this->fail('Invalid disappearing_days.', 422, 'invalid_value');
            }
            $fields['disappearing_days'] = $days;
        }
        if (!$fields) {
            $this->fail('No settings provided.', 422, 'validation_error');
        }
        $fields['updated_at'] = now_iso();
        $this->store->update('conversations', $fields, 'id = ?', [$id]);
        // System event for audit
        $change = [];
        foreach ($fields as $k => $v) { if ($k !== 'updated_at') $change[] = $k; }
        $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " mengubah pengaturan grup");
        $updated = $this->conversation_model->find($id);
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', ['conversation_id' => $id]);
        $this->ok(['settings' => [
            'only_admins_can_send' => (bool) ($updated['only_admins_can_send'] ?? false),
            'only_admins_can_edit' => (bool) ($updated['only_admins_can_edit'] ?? false),
            'only_admins_can_add' => (bool) ($updated['only_admins_can_add'] ?? false),
            'invite_link_enabled' => (bool) ($updated['invite_link_enabled'] ?? true),
            'approve_new_members' => (bool) ($updated['approve_new_members'] ?? false),
            'disappearing_days' => (int) ($updated['disappearing_days'] ?? 0),
        ]]);
    }

    private function pushSystemEvent(int $conversationId, int $actorId, string $body): void
    {
        $result = $this->message_model->create([
            'conversation_id' => $conversationId,
            'sender_id' => $actorId,
            'type' => 'system',
            'body' => $body,
        ]);
        $message = message_dto($result['row']);
        if (!$result['duplicate']) {
            $this->conversation_model->setLastMessage($conversationId, (int) $result['row']['id'], $result['row']['created_at']);
            $memberIds = $this->conversation_model->memberIds($conversationId);
            $this->realtime_model->push($memberIds, 'message', [
                'conversation_id' => $conversationId,
                'message' => $message,
            ]);
        }
    }

    /**
     * Convert a conversation row into the API shape, including the peer for
     * direct chats and the member list for group chats.
     */
    private function present(array $row, int $viewerId, bool $includeMembers = false): array
    {
        $members = $this->conversation_model->members((int) $row['id']);
        $dto = conversation_dto($row);
        if ($row['type'] === 'direct') {
            foreach ($members as $member) {
                if ((int) $member['id'] !== $viewerId) {
                    $dto['title'] = $member['display_name'];
                    $dto['avatar_url'] = $member['avatar_url'];
                    $dto['peer'] = user_dto($member);
                    break;
                }
            }
        } else {
            $dto['member_count'] = count($members);
            if ($includeMembers) {
                $dto['members'] = array_map(
                    static fn (array $member): array => user_dto($member),
                    $members
                );
            }
        }
        return $dto;
    }
}
