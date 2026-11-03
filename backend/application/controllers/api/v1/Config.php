<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Config extends MY_Controller
{
    public function index(): void
    {
        $this->require_method(['GET']);
        $this->ok([
            'app' => [
                'name' => $this->app['name'],
                'version' => $this->app['version'],
                'api_version' => 'v1',
            ],
            'features' => $this->app['features'],
            'limits' => [
                'upload_max_mb' => $this->app['upload_max_mb'],
                'message_max_chars' => 4000,
                'moment_max_chars' => 2000,
                'group_max_members' => 50,
                'password_min_chars' => 6,
            ],
            'ws_url' => $this->app['ws_url'],
            'server_time' => now_iso(),
        ]);
    }

    public function ping(): void
    {
        $this->require_method(['GET']);
        $this->ok([
            'pong' => true,
            'db' => $this->store->driver(),
            'server_time' => now_iso(),
            'version' => $this->app['version'],
        ]);
    }
}
