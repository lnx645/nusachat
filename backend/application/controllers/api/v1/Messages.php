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
}
