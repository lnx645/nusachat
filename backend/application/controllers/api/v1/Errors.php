<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Errors extends MY_Controller
{
    public function not_found(): void
    {
        $this->fail('Endpoint not found.', 404, 'not_found');
    }
}
