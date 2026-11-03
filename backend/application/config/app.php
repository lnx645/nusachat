<?php
defined('BASEPATH') OR exit('No direct script access allowed');

$config['app'] = [
    'name'          => getenv('APP_NAME') ?: 'NusaChat',
    'version'       => APP_VERSION,
    'secret'        => getenv('APP_SECRET') ?: 'dev-secret-change-me',
    'access_ttl'    => (int) (getenv('JWT_TTL') ?: 604800),
    'refresh_ttl'   => (int) (getenv('REFRESH_TTL') ?: 2592000),
    'upload_max_mb' => (int) (getenv('UPLOAD_MAX_MB') ?: 25),
    'ws_url'        => getenv('WS_PUBLIC_URL') ?: 'ws://localhost:8090',
    'cors'          => getenv('CORS_ORIGINS') ?: '*',
    'register_rate_limit' => (int) (getenv('REGISTER_RATE_LIMIT') ?: 20),
    'fcm_server_key' => getenv('FCM_SERVER_KEY') ?: '',
    'fcm_service_json' => getenv('FCM_SERVICE_JSON') ?: '',
    'fcm_project_id' => getenv('FCM_PROJECT_ID') ?: '',
    'features'      => [
        'direct_chat' => true,
        'group_chat'  => true,
        'moments'     => true,
        'nearby'      => true,
        'moments_comments' => true,
        'voice_calls' => true,
        'video_calls' => false,
        'monetization' => false,
        'push' => true,
    ],
];
