<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Nearby extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['user_model', 'friend_model']);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();

        $lat = $this->param('lat');
        $lng = $this->param('lng');
        if ($lat === null || $lng === null) {
            $this->fail('lat and lng are required.', 422, 'location_required');
        }
        $lat = (float) $lat;
        $lng = (float) $lng;
        if ($lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
            $this->fail('Invalid coordinates.', 422, 'invalid_location');
        }
        $radius = (float) ($this->param('radius', 20));
        $radius = max(1.0, min(100.0, $radius));

        $this->user_model->touch((int) $user['id'], $lat, $lng);
        $rows = $this->user_model->nearby($lat, $lng, $radius, (int) $user['id'], 50);
        $hidden = array_merge(
            $this->friend_model->blockedIds((int) $user['id']),
            $this->friend_model->blockedByIds((int) $user['id'])
        );

        $users = [];
        foreach ($rows as $row) {
            if (in_array((int) $row['id'], $hidden, true)) {
                continue;
            }
            $dto = user_dto($row);
            $dto['distance_km'] = $row['distance_km'];
            $friendship = $this->friend_model->findBetween((int) $user['id'], (int) $row['id']);
            $dto['friend_status'] = $friendship ? $friendship['status'] : 'none';
            if ($friendship) {
                $dto['request_id'] = (int) $friendship['id'];
                $dto['request_direction'] = (int) $friendship['requester_id'] === (int) $user['id'] ? 'outgoing' : 'incoming';
            }
            $users[] = $dto;
        }

        $this->ok([
            'users' => $users,
            'radius_km' => $radius,
            'location' => ['lat' => round($lat, 3), 'lng' => round($lng, 3)],
        ]);
    }
}
