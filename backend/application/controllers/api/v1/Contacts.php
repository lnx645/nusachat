<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Contacts extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['contact_model', 'friend_model']);
    }

    public function sync(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();

        $contacts = $this->param('contacts', []);
        if (!is_array($contacts)) {
            $this->fail('contacts must be an array.', 422, 'validation_error');
        }
        if (count($contacts) > 2000) {
            $this->fail('A maximum of 2000 contacts can be uploaded at once.', 422, 'too_many_contacts');
        }
        if (!$this->throttle('contacts:' . (int) $user['id'], 12, 3600)) {
            $this->fail('Too many contact syncs. Try again later.', 429, 'rate_limited');
        }

        $matches = $this->contact_model->sync((int) $user['id'], $contacts);
        $out = [];
        foreach ($matches as $row) {
            $dto = user_dto($row);
            $friendship = $this->friend_model->findBetween((int) $user['id'], (int) $row['id']);
            $dto['friend_status'] = $friendship ? $friendship['status'] : 'none';
            if ($friendship) {
                $dto['request_id'] = (int) $friendship['id'];
                $dto['request_direction'] = (int) $friendship['requester_id'] === (int) $user['id'] ? 'outgoing' : 'incoming';
            }
            $out[] = $dto;
        }

        $this->ok([
            'received' => count($contacts),
            'matched' => count($out),
            'users' => $out,
        ]);
    }
}
