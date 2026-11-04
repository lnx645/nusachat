<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Moments extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['moment_model', 'friend_model', 'realtime_model', 'user_model']);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $authorIds = $this->friend_model->friendIds((int) $user['id']);
        $authorIds[] = (int) $user['id'];

        $beforeId = $this->param('before_id') !== null ? $this->int_param('before_id') : null;
        $limit = max(1, min(50, $this->int_param('limit', 20)));
        $hidden = array_merge(
            $this->friend_model->blockedIds((int) $user['id']),
            $this->friend_model->blockedByIds((int) $user['id'])
        );
        $authorIds = array_values(array_diff($authorIds, $hidden));
        $rows = $authorIds
            ? $this->moment_model->withReshare($this->moment_model->feed($authorIds, (int) $user['id'], $beforeId, $limit))
            : [];

        $this->ok([
            'moments' => array_map('moment_dto', $rows),
            'has_more' => count($rows) >= $limit,
        ]);
    }

    public function show(): void
    {
        $this->require_method(['GET']);
        $this->require_auth();
        $id = $this->int_param('id');
        $moment = $this->moment_model->find($id);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $row = $this->store->one(
            'SELECT mo.*, u.display_name AS author_display_name, u.username AS author_username,
                    u.avatar_url AS author_avatar_url,
                    (SELECT COUNT(*) FROM moment_likes l WHERE l.moment_id = mo.id) AS like_count,
                    (SELECT COUNT(*) FROM moment_comments c WHERE c.moment_id = mo.id) AS comment_count,
                    (SELECT COUNT(*) FROM moment_likes l2 WHERE l2.moment_id = mo.id AND l2.user_id = ?) AS liked_by_me
             FROM moments mo JOIN users u ON u.id = mo.user_id WHERE mo.id = ?',
            [(int) $this->user['id'], $id]
        );
        $this->ok(['moment' => moment_dto($row)]);
    }

    public function create(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();

        $body = $this->str_param('body');
        $media = $this->param('media', []);
        if (!is_array($media)) {
            $this->fail('media must be an array of uploaded URLs.', 422, 'validation_error');
        }
        $media = array_values(array_filter(array_map('trim', $media)));
        if (count($media) > 9) {
            $this->fail('A moment can contain at most 9 media items.', 422, 'too_many_media');
        }
        if (mb_strlen($body) > 2000) {
            $this->fail('Moment text is limited to 2000 characters.', 422, 'too_long');
        }
        if ($body === '' && !$media) {
            $this->fail('Write something or attach a photo or video.', 422, 'empty_moment');
        }

        $id = $this->moment_model->create((int) $user['id'], $body, $media);
        $moment = $this->moment_model->find($id);
        $moment['author_display_name'] = $user['display_name'];
        $moment['author_username'] = $user['username'];
        $moment['author_avatar_url'] = $user['avatar_url'];
        $moment = $this->moment_model->withReshare([$moment])[0];

        $friendIds = $this->friend_model->friendIds((int) $user['id']);
        $this->realtime_model->push($friendIds, 'moment_new', [
            'moment_id' => $id,
            'author' => user_dto($user),
        ]);

        $this->ok(['moment' => moment_dto($moment)], 201);
    }

    public function delete(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        if (!$this->moment_model->softDelete($id, (int) $user['id'])) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $this->ok(['message' => 'Moment deleted.']);
    }

    public function like(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $moment = $this->moment_model->find($id);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $this->moment_model->like($id, (int) $user['id']);
        if ((int) $moment['user_id'] !== (int) $user['id']) {
            $this->realtime_model->push([(int) $moment['user_id']], 'moment_liked', [
                'moment_id' => $id,
                'user' => user_dto($user),
            ]);
            push_notify([(int) $moment['user_id']], 'Momen disukai', ($user['display_name'] ?? 'Seseorang') . ' menyukai momenmu', [
                'event' => 'moment_liked',
                'moment_id' => (string) $id,
            ]);
        }
        $this->ok(['liked' => true]);
    }

    public function unlike(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $this->moment_model->unlike($id, (int) $user['id']);
        $this->ok(['liked' => false]);
    }

    public function comments(): void
    {
        $this->require_method(['GET']);
        $this->require_auth();
        $id = $this->int_param('id');
        $moment = $this->moment_model->find($id);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $comments = [];
        foreach ($this->moment_model->comments($id) as $row) {
            $comments[] = [
                'id' => (int) $row['id'],
                'body' => $row['body'],
                'created_at' => $row['created_at'],
                'parent_id' => $row['parent_id'] !== null ? (int) $row['parent_id'] : null,
                'reply_to_user_id' => $row['reply_to_user_id'] !== null ? (int) $row['reply_to_user_id'] : null,
                'reply_to_display_name' => $row['reply_to_display_name'] ?? null,
                'reply_to_username' => $row['reply_to_username'] ?? null,
                'user' => [
                    'id' => (int) $row['user_id'],
                    'display_name' => $row['display_name'],
                    'username' => $row['username'],
                    'avatar_url' => $row['avatar_url'],
                ],
            ];
        }
        $this->ok(['comments' => $comments]);
    }

    public function comment(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'body']);
        $id = $this->int_param('id');
        $moment = $this->moment_model->find($id);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $body = $this->str_param('body');
        if (mb_strlen($body) > 500) {
            $this->fail('Comment is limited to 500 characters.', 422, 'too_long');
        }
        $parentId = $this->param('parent_id') !== null ? $this->int_param('parent_id') : null;
        $replyToUserId = null;
        if ($parentId !== null) {
            $parent = $this->store->one('SELECT * FROM moment_comments WHERE id = ? AND moment_id = ?', [$parentId, $id]);
            if (!$parent) $this->fail('Reply target not found.', 404, 'not_found');
            $replyToUserId = (int) $parent['user_id'];
        }
        $commentId = $this->moment_model->addComment($id, (int) $user['id'], $body, $parentId, $replyToUserId);
        if ((int) $moment['user_id'] !== (int) $user['id']) {
            $this->realtime_model->push([(int) $moment['user_id']], 'moment_comment', [
                'moment_id' => $id,
                'comment_id' => $commentId,
                'user' => user_dto($user),
                'body' => $body,
            ]);
            push_notify([(int) $moment['user_id']], 'Komentar baru', ($user['display_name'] ?? 'Seseorang') . ': ' . mb_substr($body, 0, 100), [
                'event' => 'moment_comment',
                'moment_id' => (string) $id,
            ]);
        }
        $replyTo = null;
        if ($replyToUserId !== null) {
            $ru = $this->store->one('SELECT display_name, username FROM users WHERE id = ?', [$replyToUserId]);
            if ($ru) $replyTo = $ru;
        }
        $this->ok([
            'comment' => [
                'id' => $commentId,
                'body' => $body,
                'created_at' => now_iso(),
                'parent_id' => $parentId,
                'reply_to_user_id' => $replyToUserId,
                'reply_to_display_name' => $replyTo['display_name'] ?? null,
                'reply_to_username' => $replyTo['username'] ?? null,
                'user' => user_dto($user),
            ],
        ], 201);
    }

    public function reshare(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);

        $id = $this->int_param('id');
        $source = $this->moment_model->find($id);
        if (!$source || $source['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $authorId = (int) $source['user_id'];
        if ($authorId !== (int) $user['id'] && !$this->friend_model->areFriends((int) $user['id'], $authorId)) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        if ($this->friend_model->isBlocked((int) $user['id'], $authorId)
            || $this->friend_model->isBlocked($authorId, (int) $user['id'])) {
            $this->fail('Moment not found.', 404, 'not_found');
        }

        $newId = $this->moment_model->reshare((int) $user['id'], $source);
        $moment = $this->moment_model->find($newId);
        $moment['author_display_name'] = $user['display_name'];
        $moment['author_username'] = $user['username'];
        $moment['author_avatar_url'] = $user['avatar_url'];
        $moment = $this->moment_model->withReshare([$moment])[0];

        $friendIds = $this->friend_model->friendIds((int) $user['id']);
        $this->realtime_model->push($friendIds, 'moment_new', [
            'moment_id' => $newId,
            'author' => user_dto($user),
        ]);
        $this->ok(['moment' => moment_dto($moment)], 201);
    }

    public function stories(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $authorIds = $this->friend_model->friendIds((int) $user['id']);
        $authorIds[] = (int) $user['id'];
        $hidden = array_merge(
            $this->friend_model->blockedIds((int) $user['id']),
            $this->friend_model->blockedByIds((int) $user['id'])
        );
        $authorIds = array_values(array_diff($authorIds, $hidden));
        $rows = $authorIds ? $this->moment_model->activeStories($authorIds) : [];
        $this->ok(['stories' => array_map('story_dto', $rows)]);
    }

    public function story_create(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();

        $body = $this->str_param('body');
        $media = $this->param('media', []);
        if (!is_array($media)) {
            $this->fail('media must be an array of uploaded URLs.', 422, 'validation_error');
        }
        $media = array_values(array_filter(array_map('trim', $media)));
        if (count($media) > 4) {
            $this->fail('A story can contain at most 4 media items.', 422, 'too_many_media');
        }
        if (mb_strlen($body) > 500) {
            $this->fail('Story text is limited to 500 characters.', 422, 'too_long');
        }
        if ($body === '' && !$media) {
            $this->fail('Write something or attach a photo or video.', 422, 'empty_story');
        }
        $ttl = $this->param('expires_in_hours') !== null ? $this->int_param('expires_in_hours', 24) : 24;
        $ttl = max(1, min(48, $ttl));

        $story = $this->moment_model->createStory((int) $user['id'], $body, $media, $ttl);
        $story['author_display_name'] = $user['display_name'];
        $story['author_username'] = $user['username'];
        $story['author_avatar_url'] = $user['avatar_url'];

        $friendIds = $this->friend_model->friendIds((int) $user['id']);
        $this->realtime_model->push($friendIds, 'story_new', [
            'story_id' => (int) $story['id'],
            'author' => user_dto($user),
        ]);
        $this->ok(['story' => story_dto($story)], 201);
    }

    public function story_delete(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        if (!$this->moment_model->deleteStory($id, (int) $user['id'])) {
            $this->fail('Story not found.', 404, 'not_found');
        }
        $this->ok(['message' => 'Story deleted.']);
    }
}
