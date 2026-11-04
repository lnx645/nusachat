<?php
defined('BASEPATH') OR exit('No direct script access allowed');

$config['app'] = [
    'env'           => getenv('APP_ENV') ?: 'production',
    'name'          => getenv('APP_NAME') ?: 'NusaChat',
    'version'       => APP_VERSION,
    'secret'        => getenv('APP_SECRET') ?: 'dev-secret-change-me',
    'access_ttl'    => (int) (getenv('JWT_TTL') ?: 604800),
    'refresh_ttl'   => (int) (getenv('REFRESH_TTL') ?: 2592000),
    'upload_max_mb' => (int) (getenv('UPLOAD_MAX_MB') ?: 25),
    'ws_url'        => getenv('WS_PUBLIC_URL') ?: 'ws://localhost:8091',
    'cors'          => getenv('CORS_ORIGINS') ?: '*',
    'cors_origins'  => array_values(array_filter(array_map(
        'trim',
        explode(',', (string) (getenv('CORS_ORIGINS') ?: '*'))
    ))),
    'trusted_proxies' => array_values(array_filter(array_map(
        'trim',
        explode(',', (string) (getenv('TRUSTED_PROXIES') ?: ''))
    ))),
    'register_rate_limit' => (int) (getenv('REGISTER_RATE_LIMIT') ?: 20),
    'google_client_ids' => getenv('GOOGLE_CLIENT_IDS') ?: '',
    'fcm_server_key' => getenv('FCM_SERVER_KEY') ?: '',
    'fcm_service_json' => getenv('FCM_SERVICE_JSON') ?: '',
    'fcm_project_id' => getenv('FCM_PROJECT_ID') ?: '',
    'turn' => [
        'app_id'    => getenv('TURN_APP_ID') ?: 'c6c1abdf36230f2bc354c4566d74fcc6',
        'api_token' => getenv('TURN_API_TOKEN') ?: '',
        'ttl'       => (int) (getenv('TURN_TTL') ?: 86400),
    ],
    'features'      => [
        'direct_chat' => true,
        'group_chat'  => true,
        'moments'     => true,
        'nearby'      => true,
        'moments_comments' => true,
        'voice_calls' => true,
        'video_calls' => true,
        'group_calls' => true,
        'monetization' => true,
        'redpackets' => true,
        'wallet_requests' => true,
        'push' => true,
    ],
];
