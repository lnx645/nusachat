<?php
defined('BASEPATH') OR exit('No direct script access allowed');

/*
 * The app uses the Store library (PDO) instead of CodeIgniter's DB layer so it
 * can run on SQLite for development and MySQL in production. This file exists
 * for compatibility with CI tooling and for optional query-builder usage.
 */
$db['default'] = [
    'dsn'      => '',
    'hostname' => getenv('DB_HOST') ?: '127.0.0.1',
    'username' => getenv('DB_USER') ?: 'root',
    'password' => getenv('DB_PASS') ?: '',
    'database' => getenv('DB_NAME') ?: 'nusachat',
    'dbdriver' => 'mysqli',
    'dbprefix' => '',
    'pconnect' => false,
    'db_debug' => (ENVIRONMENT !== 'production'),
    'cache_on' => false,
    'cachedir' => '',
    'char_set' => 'utf8mb4',
    'dbcollat' => 'utf8mb4_unicode_ci',
    'swap_pre' => '',
    'encrypt'  => false,
    'compress' => false,
    'stricton' => false,
    'failover' => [],
    'save_queries' => true,
];
