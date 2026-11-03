<?php
/**
 * NusaChat API bootstrap (CodeIgniter 3).
 *
 * Front controller for the REST API. Works with Apache/nginx and with the
 * bundled PHP development server (see router.php).
 */
define('APP_START', microtime(true));

// ---------------------------------------------------------------------------
// Environment file (.env is optional; exported variables win)
// ---------------------------------------------------------------------------
$envFile = __DIR__ . '/.env';
if (is_file($envFile)) {
    $values = parse_ini_file($envFile, false, INI_SCANNER_RAW);
    if (is_array($values)) {
        foreach ($values as $key => $value) {
            if (getenv($key) === false) {
                putenv($key . '=' . $value);
                $_ENV[$key] = $value;
            }
        }
    }
}

$environment = getenv('APP_ENV') ?: 'development';
define('ENVIRONMENT', $environment);

if (ENVIRONMENT === 'development') {
    error_reporting(E_ALL);
    ini_set('display_errors', '1');
} else {
    error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE);
    ini_set('display_errors', '0');
}

// ---------------------------------------------------------------------------
// Paths
// ---------------------------------------------------------------------------
if (($_temp = realpath(__DIR__ . '/system')) !== false) {
    $system_path = $_temp . '/';
} else {
    header('HTTP/1.1 503 Service Unavailable', true, 503);
    exit('NusaChat: system folder not found.');
}

$application_folder = __DIR__ . '/application';
$view_folder = '';

define('SELF', basename(__FILE__));
define('BASEPATH', str_replace('\\', '/', $system_path));
define('FCPATH', __DIR__ . '/');
define('SYSDIR', trim(strrchr(trim(BASEPATH, '/'), '/'), '/'));

if (($_temp = realpath($application_folder)) !== false) {
    $application_folder = $_temp;
}
define('APPPATH', $application_folder . '/');

if ($view_folder === '') {
    define('VIEWPATH', APPPATH . 'views/');
} else {
    define('VIEWPATH', rtrim($view_folder, '/\\') . '/');
}

require_once BASEPATH . 'core/CodeIgniter.php';
