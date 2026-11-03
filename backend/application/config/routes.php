<?php
defined('BASEPATH') OR exit('No direct script access allowed');

$route['default_controller'] = 'api/v1/config';
$route['404_override'] = 'api/v1/errors/not_found';
$route['translate_uri_dashes'] = false;

// Endpoints where the controller name alone should hit a named action.
$route['api/v1/uploads'] = 'api/v1/uploads/create';
