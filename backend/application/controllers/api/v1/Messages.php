<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Messages extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['message_model', 'conversation_model', 'realtime_model']);
    }

    public function revoke(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['message_id']);

        $messageId = $this->int_param('message_id');
        $message = $this->message_model->find($messageId);
        if (!$message) {
            $this->fail('Message not found.', 404, 'not_found');
        }
        if ((int) $message['sender_id'] !== (int) $user['id']) {
            $this->fail('You can only revoke your own messages.', 403, 'forbidden');
        }
        if (!$this->conversation_model->isMember((int) $message['conversation_id'], (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        if (!$this->message_model->revoke($messageId, (int) $user['id'])) {
            $this->fail('This message can no longer be revoked (24 hour limit).', 422, 'revoke_failed');
        }

        $this->realtime_model->push(
            $this->conversation_model->memberIds((int) $message['conversation_id']),
            'message_revoked',
            [
                'conversation_id' => (int) $message['conversation_id'],
                'message_id' => $messageId,
            ]
        );
        $this->ok(['message_id' => $messageId, 'revoked' => true]);
    }

    public function edit(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['message_id', 'body']);

        $messageId = $this->int_param('message_id');
        $message = $this->message_model->find($messageId);
        if (!$message) {
            $this->fail('Message not found.', 404, 'not_found');
        }
        if ((int) $message['sender_id'] !== (int) $user['id']) {
            $this->fail('You can only edit your own messages.', 403, 'forbidden');
        }
        if (!$this->conversation_model->isMember((int) $message['conversation_id'], (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $body = trim($this->str_param('body'));
        if ($body === '' || mb_strlen($body) > 4000) {
            $this->fail('Message must be 1-4000 characters.', 422, 'invalid_body');
        }
        if (!$this->message_model->edit($messageId, (int) $user['id'], $body)) {
            $this->fail('This message can no longer be edited (24 hour limit).', 422, 'edit_failed');
        }

        $rows = $this->message_model->withExtras([$this->message_model->find($messageId)], (int) $user['id']);
        $dto = message_dto($rows[0]);
        $this->realtime_model->push(
            $this->conversation_model->memberIds((int) $message['conversation_id']),
            'message_edited',
            [
                'conversation_id' => (int) $message['conversation_id'],
                'message' => $dto,
            ]
        );
        $this->ok(['message' => $dto]);
    }

    public function react(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['message_id', 'emoji']);

        $messageId = $this->int_param('message_id');
        $message = $this->message_model->find($messageId);
        if (!$message || $message['revoked_at'] !== null) {
            $this->fail('Message not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isMember((int) $message['conversation_id'], (int) $user['id'])) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $emoji = trim($this->str_param('emoji'));
        if ($emoji === '' || mb_strlen($emoji) > 12) {
            $this->fail('Invalid emoji reaction.', 422, 'invalid_emoji');
        }
        $action = $this->message_model->setReaction($messageId, (int) $user['id'], $emoji);
        $summary = $this->message_model->reactionsFor([$messageId], (int) $user['id'])[$messageId] ?? [];
        $this->realtime_model->push(
            $this->conversation_model->memberIds((int) $message['conversation_id']),
            'message_reaction',
            [
                'conversation_id' => (int) $message['conversation_id'],
                'message_id' => $messageId,
                'user_id' => (int) $user['id'],
                'action' => $action,
                'emoji' => $action === 'removed' ? null : $emoji,
                'reactions' => $summary,
            ]
        );
        $this->ok(['action' => $action, 'reactions' => $summary]);
    }
}
