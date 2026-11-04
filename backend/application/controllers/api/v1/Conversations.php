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
            'user_model', 'realtime_model', 'key_model',
        ]);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $limit = $this->param('limit') !== null ? $this->int_param('limit', 100) : 100;
        $before = $this->param('before') !== null ? $this->str_param('before') : null;
        $rows = $this->conversation_model->listFor((int) $user['id'], $limit, $before);
        $archivedOnly = $this->param('archived') !== null && $this->bool_param('archived');
        $rows = array_values(array_filter(
            $rows,
            static fn (array $r): bool => $archivedOnly ? !empty($r['archived']) : empty($r['archived'])
        ));
        $ids = array_map('intval', array_column($rows, 'id'));
        $latest = $this->message_model->latestForConversations($ids);

        $conversations = [];
        foreach ($rows as $row) {
            $lastRow = $latest[(int) $row['id']] ?? null;
            $row['last_message'] = $lastRow ? message_dto($lastRow) : null;
            $conversations[] = $this->present($row, (int) $user['id']);
        }
        $last = end($rows);
        $this->ok([
            'conversations' => $conversations,
            'has_more' => count($rows) >= max(1, min(200, $limit)),
            'next_before' => $last ? ($last['last_message_at'] ?? $last['created_at']) : null,
        ]);
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
        if ($this->friend_model->isBlocked((int) $user['id'], $targetId)) {
            $this->fail('You have blocked this user. Unblock to continue.', 403, 'blocked');
        }
        if ($this->friend_model->isBlocked($targetId, (int) $user['id'])) {
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
        $rows = $this->message_model->list($id, $beforeId, $afterId, $limit, (int) $user['id']);
        // Per-user clear history & disappearing
        $clearedAt = $this->store->value('SELECT cleared_at FROM conversation_members WHERE conversation_id = ? AND user_id = ?', [$id, (int) $user['id']]);
        if (!empty($clearedAt)) {
            $cut = strtotime($clearedAt);
            if ($cut !== false) {
                $rows = array_values(array_filter($rows, function ($m) use ($cut) {
                    $ts = strtotime($m['created_at'] ?? 'now');
                    return $ts === false || $ts > $cut;
                }));
            }
        }
        // Disappearing messages: hide pesan lama melebihi batas (kecuali system)
        if (!empty($row['disappearing_days']) && (int) $row['disappearing_days'] > 0) {
            $cutoff = time() - (int) $row['disappearing_days'] * 86400;
            $rows = array_values(array_filter($rows, function ($m) use ($cutoff) {
                if (($m['type'] ?? '') === 'system') return true;
                $ts = strtotime($m['created_at'] ?? 'now');
                return $ts === false || $ts >= $cutoff;
            }));
        }
        // Hapus-untuk-saya: saring pesan yang disembunyikan viewer.
        $hidden = $this->message_model->hiddenIds(
            (int) $user['id'],
            array_map(static fn (array $m): int => (int) $m['id'], $rows)
        );
        if ($hidden) {
            $hiddenSet = array_flip($hidden);
            $rows = array_values(array_filter(
                $rows,
                static fn (array $m): bool => !isset($hiddenSet[(int) $m['id']])
            ));
        }

        $this->message_model->markDelivered($id, (int) $user['id']);

        $rows = $this->message_model->withExtras($rows, (int) $user['id']);
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

        $this->assertCanSend($row, $user);

        $type = $this->str_param('type');
        $allowed = ['text', 'image', 'file', 'audio', 'video', 'location', 'sticker'];
        if (!in_array($type, $allowed, true)) {
            $this->fail('Unsupported message type.', 422, 'invalid_type');
        }

        $body = $this->str_param('body');
        $attachmentUrl = $this->str_param('attachment_url');
        $attachmentMeta = is_array($this->param('attachment_meta')) ? $this->param('attachment_meta') : null;
        if ($type === 'text' && $body === '') {
            $this->fail('Message cannot be empty.', 422, 'empty_message');
        }
        if ($type === 'sticker') {
            // Stiker emoji bawaan: body berisi satu emoji/id stiker pendek.
            if ($body === '' || mb_strlen($body) > 24) {
                $this->fail('Sticker must be 1-24 characters.', 422, 'invalid_sticker');
            }
        }
        if ($type === 'location') {
            $lat = $attachmentMeta['lat'] ?? null;
            $lng = $attachmentMeta['lng'] ?? null;
            if (!is_numeric($lat) || !is_numeric($lng) || (float) $lat < -90 || (float) $lat > 90 || (float) $lng < -180 || (float) $lng > 180) {
                $this->fail('Location requires lat and lng.', 422, 'invalid_location');
            }
            $attachmentMeta['lat'] = (float) $lat;
            $attachmentMeta['lng'] = (float) $lng;
        } elseif ($type !== 'text' && $type !== 'sticker' && $attachmentUrl === '') {
            $this->fail('attachment_url is required for this message type.', 422, 'missing_attachment');
        }
        // Batas 8192: amplop E2EE v2 (~300 char overhead base64+) harus muat.
        if (mb_strlen($body) > 8192) {
            $this->fail('Message is too long (max 8192 characters).', 422, 'message_too_long');
        }

        $replyTo = $this->param('reply_to_id') !== null ? $this->int_param('reply_to_id') : null;
        if ($replyTo) {
            // Relay: target balas boleh sudah disapu server (kutipan ada di cache
            // perangkat). Tolak hanya bila barisnya ada tapi beda percakapan.
            $reply = $this->message_model->find($replyTo);
            if ($reply && (int) $reply['conversation_id'] !== $id) {
                $this->fail('The message you replied to was not found.', 422, 'invalid_reply');
            }
        }

        $clientId = $this->str_param('client_id');
        if ($clientId !== '' && mb_strlen($clientId) > 80) {
            $this->fail('client_id is too long.', 422, 'invalid_client_id');
        }

        $mentions = $type === 'text' ? $this->extractMentions($id, (int) $user['id'], $body) : [];
        $result = $this->message_model->create([
            'conversation_id' => $id,
            'sender_id' => (int) $user['id'],
            'type' => $type,
            'body' => $body !== '' ? $body : null,
            'attachment_url' => $attachmentUrl !== '' ? $attachmentUrl : null,
            'attachment_meta' => $attachmentMeta,
            'reply_to_id' => $replyTo,
            'mentions' => $mentions,
            'client_id' => $clientId !== '' ? $clientId : null,
            'member_ids' => $this->conversation_model->memberIds($id),
        ]);

        $rows = $this->message_model->withExtras([$result['row']], (int) $user['id']);
        $message = message_dto($rows[0]);
        // silent: pesan mesin (SKDM E2EE) — tetap event realtime, tanpa push.
        $silent = $this->bool_param('silent');
        if (!$result['duplicate']) {
            $this->broadcastNewMessage($id, $user, $result['row'], $message, $body, $type, $silent);
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
        // Relay: pesan boleh sudah disapu; penanda baca tetap maju (idempotent).
        // Tolak hanya bila barisnya ada tapi beda percakapan.
        $message = $this->message_model->find($messageId);
        if ($message && (int) $message['conversation_id'] !== $id) {
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
            static function (array $row): array {
                $dto = user_dto($row);
                $dto['role'] = $row['role'] ?? 'member';
                return $dto;
            },
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
        $myId = (int) $user['id'];
        $isOwner = $this->conversation_model->isOwner($id, $myId);
        $isAdmin = $this->conversation_model->isAdmin($id, $myId);
        if ($targetId !== $myId) {
            if (!$isAdmin) {
                $this->fail('You can only leave the group yourself.', 403, 'forbidden');
            }
            if (!$isOwner) {
                $targetRole = $this->conversation_model->roleOf($id, $targetId);
                if ($targetRole !== 'member') {
                    $this->fail('Only the owner can remove admins.', 403, 'forbidden');
                }
            }
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

    public function clear(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $this->store->update(
            'conversation_members',
            ['cleared_at' => now_iso(), 'last_read_message_id' => 0],
            'conversation_id = ? AND user_id = ?',
            [$id, (int) $user['id']]
        );
        $this->ok(['cleared' => true]);
    }

    public function forward(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['message_id', 'to_id']);

        $messageId = $this->int_param('message_id');
        $toId = $this->int_param('to_id');
        $source = $this->message_model->find($messageId);
        if ($source && $source['revoked_at'] !== null) {
            $this->fail('Message not found.', 404, 'not_found');
        }
        if ($source && !$this->conversation_model->isMember((int) $source['conversation_id'], (int) $user['id'])) {
            $this->fail('Message not found.', 404, 'not_found');
        }
        $target = $this->conversation_model->forUser($toId, (int) $user['id']);
        if (!$target) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $this->assertCanSend($target, $user);

        $bodyOverride = $this->str_param('body');
        if (mb_strlen($bodyOverride) > 8192) {
            $this->fail('Message is too long (max 8192 characters).', 422, 'message_too_long');
        }
        if ($source) {
            $meta = null;
            if (!empty($source['attachment_meta'])) {
                $decoded = json_decode($source['attachment_meta'], true);
                if (is_array($decoded)) {
                    $meta = $decoded;
                }
            }
            $forwardType = $source['type'];
            $body = (string) ($source['body'] ?? '');
            // Klien E2EE boleh mengirim ulang isi yang sudah dienkripsi dengan kunci
            // percakapan tujuan; tanpa ini, pesan lintas-obrolan tidak bisa dibaca.
            if ($bodyOverride !== '') {
                $body = $bodyOverride;
            }
            $attachmentUrl = $source['attachment_url'] ?? null;
        } else {
            // Relay: sumber sudah disapu server; klien memasok ulang isi dari cache.
            $forwardType = $this->str_param('type');
            $allowed = ['text', 'image', 'file', 'audio', 'video', 'location', 'sticker'];
            if (!in_array($forwardType, $allowed, true)) {
                $this->fail('The original message expired; include type and body from your device.', 422, 'source_expired');
            }
            if ($forwardType === 'text' && $bodyOverride === '') {
                $this->fail('Message cannot be empty.', 422, 'empty_message');
            }
            $body = $bodyOverride;
            $rawUrl = $this->str_param('attachment_url');
            $attachmentUrl = $rawUrl !== '' ? $rawUrl : null;
            if ($forwardType !== 'text' && $forwardType !== 'sticker' && $attachmentUrl === null) {
                $this->fail('The original attachment expired; include attachment_url from your device.', 422, 'source_expired');
            }
            $rawMeta = $this->param('attachment_meta');
            $meta = is_array($rawMeta) ? $rawMeta : null;
        }
        $mentions = $forwardType === 'text' ? $this->extractMentions($toId, (int) $user['id'], $body) : [];
        $clientId = $this->str_param('client_id');
        $result = $this->message_model->create([
            'conversation_id' => $toId,
            'sender_id' => (int) $user['id'],
            'type' => $forwardType,
            'body' => $body !== '' ? $body : null,
            'attachment_url' => $attachmentUrl,
            'attachment_meta' => $meta,
            'reply_to_id' => null,
            'forwarded_from_id' => $messageId,
            'mentions' => $mentions,
            'client_id' => $clientId !== '' ? $clientId : null,
            'member_ids' => $this->conversation_model->memberIds($toId),
        ]);

        $rows = $this->message_model->withExtras([$result['row']], (int) $user['id']);
        $message = message_dto($rows[0]);
        if (!$result['duplicate']) {
            $this->broadcastNewMessage($toId, $user, $result['row'], $message, $body, $forwardType);
        }
        $this->ok(['message' => $message, 'duplicate' => $result['duplicate']], $result['duplicate'] ? 200 : 201);
    }

    /**
     * Ack-sampai relay: "pesan ≤ message_id sudah tersimpan di perangkatku".
     * Idempotent; message_id tak dikenal tetap 200. Pesan yang semua
     * penerimanya ack masuk masa tenggang 24 jam sebelum disapu.
     */
    public function ack(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'message_id']);

        $id = $this->int_param('id');
        $messageId = $this->int_param('message_id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }

        $fullyAcked = $this->message_model->ackUpTo($id, (int) $user['id'], $messageId);

        $others = array_values(array_diff($this->conversation_model->memberIds($id), [(int) $user['id']]));
        $this->realtime_model->push($others, 'ack', [
            'conversation_id' => $id,
            'user_id' => (int) $user['id'],
            'message_id' => $messageId,
            'fully_acked' => $fullyAcked,
        ]);
        $this->ok(['message_id' => $messageId, 'fully_acked' => $fullyAcked]);
    }

    public function search(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $query = trim($this->str_param('q'));
        if (mb_strlen($query) < 2 || mb_strlen($query) > 100) {
            $this->fail('Search query must be 2-100 characters.', 422, 'invalid_query');
        }
        $limit = $this->int_param('limit', 20);
        $rows = $this->message_model->search($id, $query, $limit);
        $clearedAt = $this->store->value('SELECT cleared_at FROM conversation_members WHERE conversation_id = ? AND user_id = ?', [$id, (int) $user['id']]);
        if (!empty($clearedAt)) {
            $cut = strtotime($clearedAt);
            if ($cut !== false) {
                $rows = array_values(array_filter($rows, function ($m) use ($cut) {
                    $ts = strtotime($m['created_at'] ?? 'now');
                    return $ts === false || $ts > $cut;
                }));
            }
        }
        $hidden = $this->message_model->hiddenIds(
            (int) $user['id'],
            array_map(static fn (array $m): int => (int) $m['id'], $rows)
        );
        if ($hidden) {
            $hiddenSet = array_flip($hidden);
            $rows = array_values(array_filter(
                $rows,
                static fn (array $m): bool => !isset($hiddenSet[(int) $m['id']])
            ));
        }
        $rows = $this->message_model->withExtras($rows, (int) $user['id']);
        $this->ok([
            'messages' => array_map('message_dto', $rows),
            'conversation_id' => $id,
        ]);
    }

    public function pin(): void
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
        if (!$message || (int) $message['conversation_id'] !== $id || $message['revoked_at'] !== null) {
            $this->fail('Message not found.', 404, 'not_found');
        }
        $this->message_model->pin($id, $messageId, (int) $user['id']);
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_pinned', [
            'conversation_id' => $id,
            'message_id' => $messageId,
            'user_id' => (int) $user['id'],
        ]);
        $this->ok(['pins' => $this->presentPins($id, (int) $user['id'])]);
    }

    public function unpin(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'message_id']);

        $id = $this->int_param('id');
        $messageId = $this->int_param('message_id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $this->message_model->unpin($id, $messageId);
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_unpinned', [
            'conversation_id' => $id,
            'message_id' => $messageId,
            'user_id' => (int) $user['id'],
        ]);
        $this->ok(['pins' => $this->presentPins($id, (int) $user['id'])]);
    }

    public function pins(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $this->ok(['pins' => $this->presentPins($id, (int) $user['id']), 'conversation_id' => $id]);
    }

    public function archive(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $archived = $this->param('archived') !== null ? $this->bool_param('archived') : true;
        $this->store->update(
            'conversation_members',
            ['archived' => $archived ? 1 : 0],
            'conversation_id = ? AND user_id = ?',
            [$id, (int) $user['id']]
        );
        $this->ok(['archived' => $archived]);
    }

    public function pin_chat(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $pinned = $this->param('pinned') !== null ? $this->bool_param('pinned') : true;
        $this->conversation_model->setPinned($id, (int) $user['id'], $pinned);
        $this->ok(['pinned' => $pinned]);
    }

    public function mark_unread(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $messageId = $this->param('message_id') !== null ? $this->int_param('message_id') : 0;
        if ($messageId > 0) {
            $message = $this->message_model->find($messageId);
            if (!$message || (int) $message['conversation_id'] !== $id) {
                $this->fail('Message not found.', 404, 'not_found');
            }
        }
        $lastRead = $this->conversation_model->markUnread($id, (int) $user['id'], $messageId);
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        $this->ok(['last_read_message_id' => $lastRead, 'unread_count' => (int) ($row['unread_count'] ?? 0)]);
    }

    public function search_all(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $query = trim($this->str_param('q'));
        if (mb_strlen($query) < 2 || mb_strlen($query) > 100) {
            $this->fail('Search query must be 2-100 characters.', 422, 'invalid_query');
        }
        if (!$this->throttle('searchall:' . (int) $user['id'], 30, 60)) {
            $this->fail('Too many searches. Please slow down.', 429, 'rate_limited');
        }
        $limit = $this->int_param('limit', 30);
        $rows = $this->message_model->withExtras(
            $this->message_model->searchAll((int) $user['id'], $query, $limit),
            (int) $user['id']
        );
        $this->ok(['messages' => array_map(
            static function (array $row): array {
                $dto = message_dto($row);
                $dto['conversation_title'] = $row['conversation_title'] ?? null;
                $dto['conversation_type'] = $row['conversation_type'] ?? null;
                return $dto;
            },
            $rows
        )]);
    }

    /**
     * Siapa saja yang sudah membaca pesan ini (last_read >= message_id).
     */
    public function message_reads(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $messageId = $this->int_param('message_id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        // Relay: pesan boleh sudah disapu; daftar pembaca dari penanda anggota.
        $message = $this->message_model->find($messageId);
        if ($message && (int) $message['conversation_id'] !== $id) {
            $this->fail('Message not found.', 404, 'not_found');
        }
        $rows = $this->store->all(
            'SELECT u.id, u.username, u.display_name, u.avatar_url, u.gender
             FROM conversation_members m JOIN users u ON u.id = m.user_id
             WHERE m.conversation_id = ? AND m.last_read_message_id >= ? AND u.is_active = 1
             ORDER BY u.display_name ASC LIMIT 100',
            [$id, $messageId]
        );
        $this->ok([
            'message_id' => $messageId,
            'read_by' => array_map(
                static fn (array $r): array => [
                    'id' => (int) $r['id'],
                    'username' => $r['username'] ?? null,
                    'display_name' => $r['display_name'] ?? null,
                    'avatar_url' => $r['avatar_url'] ?? null,
                    'gender' => $r['gender'] ?? null,
                ],
                $rows
            ),
        ]);
    }

    public function transfer_owner(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'user_id']);

        $id = $this->int_param('id');
        $targetId = $this->int_param('user_id');
        $row = $this->conversation_model->find($id);
        if (!$row || $row['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isOwner($id, (int) $user['id'])) {
            $this->fail('Only the owner can transfer ownership.', 403, 'forbidden');
        }
        if ($targetId === (int) $user['id']) {
            $this->fail('You already own this group.', 422, 'invalid_target');
        }
        if (!$this->conversation_model->isMember($id, $targetId)) {
            $this->fail('This user is not a member of the group.', 404, 'not_found');
        }
        $this->conversation_model->transferOwnership($id, $targetId);
        $targetUser = $this->user_model->find($targetId);
        $targetName = $targetUser['display_name'] ?? ('#' . $targetId);
        $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . ' mengalihkan kepemilikan grup ke ' . $targetName);
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', [
            'conversation_id' => $id,
        ]);
        $this->ok(['owner_id' => $targetId]);
    }

    public function invite(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        $id = $this->int_param('id');
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        if (!$row || $row['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        if (empty($row['invite_link_enabled'])) {
            $this->fail('Invite links are disabled for this group.', 403, 'invite_disabled');
        }
        $rotate = $this->param('rotate') !== null && $this->bool_param('rotate');
        $token = $rotate
            ? $this->conversation_model->rotateInviteToken($id)
            : $this->conversation_model->ensureInviteToken($id);
        $this->ok([
            'invite_token' => $token,
            'invite_link' => 'nusachat://group/' . $token,
        ]);
    }

    public function join(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['token']);

        $token = trim($this->str_param('token'));
        // Terima tautan penuh maupun token mentah.
        if (str_contains($token, '/')) {
            $parts = explode('/', rtrim($token, '/'));
            $token = end($parts);
        }
        if (!preg_match('/^[0-9a-f]{16,64}$/', $token)) {
            $this->fail('Invalid invite token.', 422, 'invalid_token');
        }
        $group = $this->conversation_model->findByInviteToken($token);
        if (!$group || $group['type'] !== 'group') {
            $this->fail('Invite link is invalid.', 404, 'not_found');
        }
        $id = (int) $group['id'];
        if (empty($group['invite_link_enabled'])) {
            $this->fail('Invite links are disabled for this group.', 403, 'invite_disabled');
        }
        $target = $this->user_model->find((int) $user['id']);
        if (!$target || (int) $target['is_active'] !== 1) {
            $this->fail('Account is disabled.', 403, 'account_disabled');
        }
        if ($this->conversation_model->memberCount($id) >= 50) {
            $this->fail('This group is full (50 members).', 422, 'group_full');
        }

        $result = $this->conversation_model->requestJoin($id, (int) $user['id'], !empty($group['approve_new_members']));
        if ($result['status'] === 'member') {
            $row = $this->conversation_model->forUser($id, (int) $user['id']);
            $this->ok(['status' => 'member', 'conversation' => $this->present($row, (int) $user['id'], true)]);
        }
        if ($result['status'] === 'pending') {
            $admins = array_filter(
                $this->conversation_model->memberIds($id),
                fn (int $uid): bool => $this->conversation_model->isAdmin($id, $uid)
            );
            if ($admins) {
                $this->realtime_model->push(array_values($admins), 'join_requested', [
                    'conversation_id' => $id,
                    'user' => user_dto($target),
                ]);
            }
            $this->ok(['status' => 'pending', 'request_id' => (int) ($result['request']['id'] ?? 0)], 202);
        }
        $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " bergabung via tautan undangan");
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', [
            'conversation_id' => $id,
        ]);
        $row = $this->conversation_model->forUser($id, (int) $user['id']);
        $this->ok(['status' => 'joined', 'conversation' => $this->present($row, (int) $user['id'], true)], 201);
    }

    public function join_requests(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $row = $this->conversation_model->find($id);
        if (!$row || $row['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isAdmin($id, (int) $user['id'])) {
            $this->fail('Only admins can review join requests.', 403, 'admin_only');
        }
        $requests = array_map(
            static fn (array $r): array => [
                'request_id' => (int) $r['id'],
                'status' => $r['status'],
                'created_at' => $r['created_at'],
                'user' => [
                    'id' => (int) $r['user_id'],
                    'username' => $r['username'] ?? null,
                    'display_name' => $r['display_name'] ?? null,
                    'avatar_url' => $r['avatar_url'] ?? null,
                ],
            ],
            $this->conversation_model->pendingJoinRequests($id)
        );
        $this->ok(['requests' => $requests, 'conversation_id' => $id]);
    }

    public function review_request(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['request_id', 'action']);

        $requestId = $this->int_param('request_id');
        $action = strtolower(trim($this->str_param('action')));
        if (!in_array($action, ['approve', 'reject'], true)) {
            $this->fail('Action must be approve or reject.', 422, 'invalid_action');
        }
        $pending = $this->store->one('SELECT * FROM group_join_requests WHERE id = ?', [$requestId]);
        if (!$pending) {
            $this->fail('Join request not found.', 404, 'not_found');
        }
        $id = (int) $pending['conversation_id'];
        if (!$this->conversation_model->isAdmin($id, (int) $user['id'])) {
            $this->fail('Only admins can review join requests.', 403, 'admin_only');
        }
        if ($action === 'approve' && $this->conversation_model->memberCount($id) >= 50) {
            $this->fail('This group is full (50 members).', 422, 'group_full');
        }
        $reviewed = $this->conversation_model->reviewJoinRequest($requestId, $action === 'approve');
        if (!$reviewed) {
            $this->fail('Join request was already reviewed.', 422, 'already_reviewed');
        }
        if ($action === 'approve') {
            $newMember = $this->user_model->find((int) $pending['user_id']);
            $name = $newMember['display_name'] ?? ("#" . (int) $pending['user_id']);
            $this->pushSystemEvent($id, (int) $user['id'], $user['display_name'] . " menyetujui " . $name . " bergabung");
        }
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', [
            'conversation_id' => $id,
        ]);
        $this->realtime_model->push([(int) $pending['user_id']], 'join_reviewed', [
            'conversation_id' => $id,
            'status' => $action === 'approve' ? 'approved' : 'rejected',
        ]);
        $this->ok(['status' => $action === 'approve' ? 'approved' : 'rejected']);
    }

    public function set_role(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'user_id', 'role']);

        $id = $this->int_param('id');
        $targetId = $this->int_param('user_id');
        $role = strtolower(trim($this->str_param('role')));
        if (!in_array($role, ['admin', 'member'], true)) {
            $this->fail('Role must be admin or member.', 422, 'invalid_role');
        }
        $row = $this->conversation_model->find($id);
        if (!$row || $row['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isOwner($id, (int) $user['id'])) {
            $this->fail('Only the owner can change roles.', 403, 'forbidden');
        }
        if ($targetId === (int) $user['id']) {
            $this->fail('You cannot change your own role.', 422, 'invalid_target');
        }
        if (!$this->conversation_model->isMember($id, $targetId)) {
            $this->fail('This user is not a member of the group.', 404, 'not_found');
        }
        $targetRole = $this->conversation_model->roleOf($id, $targetId);
        if ($targetRole === 'owner') {
            $this->fail('Ownership cannot be reassigned this way.', 422, 'invalid_target');
        }
        $this->conversation_model->setRole($id, $targetId, $role);
        $targetUser = $this->user_model->find($targetId);
        $targetName = $targetUser['display_name'] ?? ("#" . $targetId);
        $this->pushSystemEvent(
            $id,
            (int) $user['id'],
            $user['display_name'] . ($role === 'admin' ? " mengangkat " . $targetName . " menjadi admin" : " mencabut admin " . $targetName)
        );
        $this->realtime_model->push($this->conversation_model->memberIds($id), 'conversation_updated', [
            'conversation_id' => $id,
        ]);
        $this->ok(['user_id' => $targetId, 'role' => $role]);
    }

    public function keys(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $holderRows = $this->key_model->keyHolders($id);
        $holders = array_values(array_unique(array_map(
            static fn (array $r): int => (int) $r['user_id'],
            $holderRows
        )));
        $devices = array_map(
            static fn (array $r): array => [
                'user_id' => (int) $r['user_id'],
                'device_id' => (string) ($r['device_id'] ?? ''),
                'key_fp' => (string) ($r['key_fp'] ?? ''),
            ],
            $holderRows
        );
        $deviceId = trim((string) ($this->param('device_id') ?? ''));
        $this->ok([
            'conversation_id' => $id,
            'my_key' => $this->key_model->wrappedKeyFor($id, (int) $user['id'], $deviceId),
            'holders' => $holders,
            'devices' => $devices,
        ]);
    }

    public function set_keys(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'keys']);

        $id = $this->int_param('id');
        if (!$this->conversation_model->isMember($id, (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $keys = $this->param('keys');
        if (!is_array($keys) || !$keys || count($keys) > 50) {
            $this->fail('keys must be a non-empty array (max 50).', 422, 'validation_error');
        }
        $stored = 0;
        $targets = [];
        foreach ($keys as $entry) {
            if (!is_array($entry) || !isset($entry['user_id'], $entry['wrapped_key'])) {
                continue;
            }
            $targetId = (int) $entry['user_id'];
            $wrapped = trim((string) $entry['wrapped_key']);
            $targetDevice = isset($entry['device_id']) ? trim((string) $entry['device_id']) : '';
            if ($targetId <= 0 || $wrapped === '' || mb_strlen($wrapped) > 2048
                || mb_strlen($targetDevice) > 128
                || base64_decode($wrapped, true) === false) {
                continue;
            }
            if (!$this->conversation_model->isMember($id, $targetId)) {
                continue;
            }
            $this->key_model->upsertWrappedKey($id, $targetId, $targetDevice, $wrapped);
            $stored++;
            if ($targetId !== (int) $user['id']) {
                $targets[$targetId] = $targetId;
            }
        }
        // Beri tahu penerima agar mengunduh kunci lalu mendekrip ulang pesan.
        if ($targets !== []) {
            $this->realtime_model->push(array_values($targets), 'keys_updated', [
                'conversation_id' => $id,
            ]);
        }
        $this->ok(['stored' => $stored], 201);
    }

    /**
     * @return array<int, array{message: array, pinned_by: int, pinned_at: string}>
     */
    private function presentPins(int $conversationId, int $viewerId): array
    {
        $rows = $this->message_model->withExtras($this->message_model->pins($conversationId), $viewerId);
        return array_map(
            static fn (array $row): array => [
                'message' => message_dto($row),
                'pinned_by' => (int) ($row['pinned_by'] ?? 0),
                'pinned_at' => $row['pinned_at'] ?? null,
            ],
            $rows
        );
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
        if ($this->param('description') !== null) {
            $description = trim($this->str_param('description'));
            if (mb_strlen($description) > 300) {
                $this->fail('Group description is limited to 300 characters.', 422, 'invalid_description');
            }
            $fields['description'] = $description === '' ? null : $description;
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

    /**
     * Cek izin kirim: grup khusus-admin dan direct yang sudah bukan teman.
     */
    private function assertCanSend(array $row, array $user): void
    {
        $id = (int) $row['id'];
        if ($row['type'] === 'group' && !empty($row['only_admins_can_send']) && (int) $row['created_by'] !== (int) $user['id']) {
            $role = $this->store->value('SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?', [$id, (int) $user['id']]);
            if ($role !== 'owner' && $role !== 'admin') {
                $this->fail('Only admins can send messages in this group.', 403, 'admin_only');
            }
        }
        if ($row['type'] === 'direct') {
            $peerId = null;
            foreach ($this->conversation_model->members($id) as $m) {
                if ((int) $m['id'] !== (int) $user['id']) { $peerId = (int) $m['id']; break; }
            }
            if ($peerId !== null) {
                if ($this->friend_model->isBlocked((int) $user['id'], $peerId)) {
                    $this->fail('You have blocked this user. Unblock to continue.', 403, 'blocked');
                }
                if ($this->friend_model->isBlocked($peerId, (int) $user['id'])) {
                    $this->fail('Conversation not found.', 404, 'not_found');
                }
                if (!$this->friend_model->areFriends((int) $user['id'], $peerId)) {
                    $this->fail('Anda tidak dapat mengirim pesan karena Anda dan teman tidak saling berteman.', 403, 'not_friends');
                }
            }
        }
    }

    private function broadcastNewMessage(int $id, array $user, array $row, array $message, string $body, string $type, bool $silent = false): void
    {
        $this->conversation_model->setLastMessage($id, (int) $row['id'], $row['created_at']);
        $memberIds = $this->conversation_model->memberIds($id);
        $this->realtime_model->push($memberIds, 'message', [
            'conversation_id' => $id,
            'message' => $message,
        ]);
        if ($silent) {
            return;
        }
        // Isi pesan teks tidak pernah dikirim ke push: chat E2EE menyimpan
        // ciphertext (bocor bila dikirim mentah), dan isi plaintext pun privat.
        // Klien mendekrip lokal bila kuncinya ada (NusaFirebaseService).
        $preview = match ($type) {
            'text' => 'Pesan baru',
            'image' => 'Mengirim foto',
            'video' => 'Mengirim video',
            'audio' => 'Mengirim pesan suara',
            'location' => 'Berbagi lokasi',
            'sticker' => 'Mengirim stiker',
            'redpacket' => 'Mengirim amplop merah',
            default => 'Mengirim lampiran',
        };
        $unmutedIds = $this->unmutedMemberIds($id, (int) $user['id']);
        if ($unmutedIds) {
            push_notify($unmutedIds, (string) ($user['display_name'] ?? 'Pesan baru'), $preview, [
                'event' => 'message',
                'conversation_id' => (string) $id,
                'message_id' => (string) $row['id'],
            ]);
        }
    }

    /**
     * @return int[]
     */
    private function unmutedMemberIds(int $conversationId, int $exceptUserId): array
    {
        $memberIds = $this->conversation_model->memberIds($conversationId);
        $targets = array_values(array_diff($memberIds, [$exceptUserId]));
        if (!$targets) {
            return [];
        }
        $unmuted = $this->store->all(
            'SELECT user_id FROM conversation_members WHERE conversation_id = ? AND user_id IN (' .
            implode(',', array_fill(0, count($targets), '?')) . ') AND (muted IS NULL OR muted = 0)',
            array_merge([$conversationId], $targets)
        );
        return array_map(static fn (array $r): int => (int) $r['user_id'], $unmuted);
    }

    /**
     * Petakan @username di isi pesan ke id anggota percakapan.
     *
     * @return int[]
     */
    private function extractMentions(int $conversationId, int $senderId, string $body): array
    {
        if (!str_contains($body, '@')) {
            return [];
        }
        $members = $this->conversation_model->members($conversationId);
        // @semua / @all menyebut semua anggota (selain pengirim).
        if (preg_match('/@(semua|all)\b/iu', $body)) {
            $all = [];
            foreach ($members as $member) {
                if ((int) $member['id'] !== $senderId) {
                    $all[] = (int) $member['id'];
                }
            }
            return $all;
        }
        if (!preg_match_all('/@([A-Za-z0-9._]{4,20})/', $body, $matches)) {
            return [];
        }
        $byName = [];
        foreach ($members as $member) {
            if (isset($member['username'])) {
                $byName[strtolower((string) $member['username'])] = (int) $member['id'];
            }
        }
        $ids = [];
        foreach (array_unique($matches[1]) as $name) {
            $key = strtolower($name);
            if (isset($byName[$key]) && $byName[$key] !== $senderId) {
                $ids[] = $byName[$key];
            }
        }
        return array_values(array_unique($ids));
    }

    private function pushSystemEvent(int $conversationId, int $actorId, string $body): void
    {
        $result = $this->message_model->create([
            'conversation_id' => $conversationId,
            'sender_id' => $actorId,
            'type' => 'system',
            'body' => $body,
            'member_ids' => $this->conversation_model->memberIds($conversationId),
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
            // E2EE key healing butuh daftar anggota walau chat 1-1.
            $dto['members'] = array_map(
                static function (array $member): array {
                    $dto = user_dto($member);
                    $dto['role'] = $member['role'] ?? 'member';
                    return $dto;
                },
                $members
            );
            foreach ($members as $member) {
                if ((int) $member['id'] !== $viewerId) {
                    $remark = $this->friend_model->remark($viewerId, (int) $member['id']);
                    $dto['title'] = $remark ?? $member['display_name'];
                    $dto['avatar_url'] = $member['avatar_url'];
                    $peer = user_dto($member);
                    $peer['remark'] = $remark;
                    // Attach friendship status for public profile / button
                    $fs = $this->friend_model->findBetween($viewerId, (int) $member['id']);
                    if ($fs) {
                        $peer['friend_status'] = $fs['status'];
                        $peer['request_id'] = (int) $fs['id'];
                        $peer['request_direction'] = (int) $fs['requester_id'] === $viewerId ? 'outgoing' : 'incoming';
                    } else {
                        $peer['friend_status'] = 'none';
                        $peer['request_id'] = null;
                        $peer['request_direction'] = null;
                    }
                    $dto['peer'] = $peer;
                    break;
                }
            }
        } else {
            $dto['member_count'] = count($members);
            if (!empty($row['invite_link_enabled']) && !empty($row['invite_token'])) {
                $dto['invite_token'] = (string) $row['invite_token'];
            } else {
                $dto['invite_token'] = null;
            }
            if ($includeMembers) {
                $dto['members'] = array_map(
                    static function (array $member): array {
                        $dto = user_dto($member);
                        $dto['role'] = $member['role'] ?? 'member';
                        return $dto;
                    },
                    $members
                );
            }
        }
        return $dto;
    }
}
