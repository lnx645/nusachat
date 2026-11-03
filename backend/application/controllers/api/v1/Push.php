<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Push extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['push_model']);
        $this->load->library('fcm');
    }

    private function fcm(): Fcm
    {
        return new Fcm(
            $this->app['fcm_server_key'] ?? '',
            $this->app['fcm_service_json'] ?? '',
            $this->app['fcm_project_id'] ?? ''
        );
    }

    public function register(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['token']);

        $token = $this->str_param('token');
        if (strlen($token) < 10 || strlen($token) > 500) {
            $this->fail('Invalid FCM token.', 422, 'invalid_token');
        }
        $platform = $this->str_param('platform', 'android') ?: 'android';
        $this->push_model->save((int) $user['id'], $token, substr($platform, 0, 16));
        $this->ok([
            'registered' => true,
            'push_enabled' => $this->fcm()->enabled(),
        ]);
    }

    public function unregister(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $token = $this->str_param('token');
        if ($token !== '') {
            $this->push_model->remove((int) $user['id'], $token);
        }
        $this->ok(['unregistered' => true]);
    }

    public function status(): void
    {
        $this->require_method(['GET']);
        $this->require_auth();
        $this->ok(['push_enabled' => $this->fcm()->enabled()]);
    }
}
