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
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $moment = $this->moment_model->find($id);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $this->assertCanSeeMoment($moment, (int) $user['id']);
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
        $audience = strtolower(trim($this->str_param('audience', 'friends')));
        if (!in_array($audience, ['friends', 'private'], true)) {
            $this->fail('audience must be friends or private.', 422, 'invalid_audience');
        }

        $id = $this->moment_model->create((int) $user['id'], $body, $media, null, $audience);
        $moment = $this->moment_model->find($id);
        $moment['author_display_name'] = $user['display_name'];
        $moment['author_username'] = $user['username'];
        $moment['author_avatar_url'] = $user['avatar_url'];
        $moment = $this->moment_model->withReshare([$moment])[0];

        if ($audience !== 'private') {
            $friendIds = $this->friend_model->friendIds((int) $user['id']);
            $this->realtime_model->push($friendIds, 'moment_new', [
                'moment_id' => $id,
                'author' => user_dto($user),
            ]);
        }

        $this->ok(['moment' => moment_dto($moment)], 201);
    }

    public function edit(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id', 'body']);

        $id = $this->int_param('id');
        $body = $this->str_param('body');
        if ($body === '' || mb_strlen($body) > 2000) {
            $this->fail('Moment text must be 1-2000 characters.', 422, 'invalid_body');
        }
        if (!$this->moment_model->edit($id, (int) $user['id'], $body)) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $moment = $this->moment_model->find($id);
        $moment['author_display_name'] = $user['display_name'];
        $moment['author_username'] = $user['username'];
        $moment['author_avatar_url'] = $user['avatar_url'];
        $this->ok(['moment' => moment_dto($moment)]);
    }

    public function likes(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $moment = $this->moment_model->find($id);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $this->assertCanSeeMoment($moment, (int) $user['id']);
        $this->ok(['users' => array_map(
            static fn (array $r): array => [
                'id' => (int) $r['id'],
                'username' => $r['username'] ?? null,
                'display_name' => $r['display_name'] ?? null,
                'avatar_url' => $r['avatar_url'] ?? null,
                'gender' => $r['gender'] ?? null,
                'liked_at' => $r['liked_at'] ?? null,
            ],
            $this->moment_model->likers($id)
        )]);
    }

    public function comment_delete(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $this->require_fields(['id']);
        if (!$this->moment_model->deleteComment($this->int_param('id'), (int) $user['id'])) {
            $this->fail('Comment not found.', 404, 'not_found');
        }
        $this->ok(['deleted' => true]);
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
        $this->assertCanSeeMoment($moment, (int) $user['id']);
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
        $user = $this->require_auth();
        $id = $this->int_param('id');
        $moment = $this->moment_model->find($id);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $comments = [];
        foreach ($this->moment_model->comments($id, (int) $user['id']) as $row) {
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
                'like_count' => (int) ($row['like_count'] ?? 0),
                'liked_by_me' => (bool) ($row['liked_by_me'] ?? false),
            ];
        }
        $this->ok(['comments' => $comments]);
    }

    public function like_comment(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);

        $comment = $this->store->one('SELECT * FROM moment_comments WHERE id = ?', [$this->int_param('id')]);
        if (!$comment) {
            $this->fail('Comment not found.', 404, 'not_found');
        }
        $moment = $this->moment_model->find((int) $comment['moment_id']);
        if (!$moment || $moment['deleted_at'] !== null) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
        $this->assertCanSeeMoment($moment, (int) $user['id']);
        $action = $this->moment_model->toggleCommentLike((int) $comment['id'], (int) $user['id'], true);
        $this->ok(['action' => $action, 'like_count' => $this->moment_model->commentLikeCount((int) $comment['id'])]);
    }

    public function unlike_comment(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $this->require_fields(['id']);

        $commentId = $this->int_param('id');
        $action = $this->moment_model->toggleCommentLike($commentId, (int) $user['id'], false);
        $this->ok(['action' => $action, 'like_count' => $this->moment_model->commentLikeCount($commentId)]);
    }

    public function tag(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $tag = ltrim(trim($this->str_param('tag')), '#');
        if (!preg_match('/^[A-Za-z0-9_]{2,30}$/', $tag)) {
            $this->fail('Tag must be 2-30 characters: letters, numbers, underscore.', 422, 'invalid_tag');
        }
        $authorIds = $this->friend_model->friendIds((int) $user['id']);
        $authorIds[] = (int) $user['id'];
        $hidden = array_merge(
            $this->friend_model->blockedIds((int) $user['id']),
            $this->friend_model->blockedByIds((int) $user['id'])
        );
        $authorIds = array_values(array_diff($authorIds, $hidden));
        $beforeId = $this->param('before_id') !== null ? $this->int_param('before_id') : null;
        $limit = max(1, min(50, $this->int_param('limit', 20)));
        $rows = $authorIds
            ? $this->moment_model->withReshare($this->moment_model->tagged($authorIds, (int) $user['id'], $tag, $beforeId, $limit))
            : [];
        $this->ok([
            'tag' => $tag,
            'moments' => array_map('moment_dto', $rows),
            'has_more' => count($rows) >= $limit,
        ]);
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
        $this->assertCanSeeMoment($moment, (int) $user['id']);
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
        $counts = $this->moment_model->storyViewCounts(
            array_map(static fn (array $r): int => (int) $r['id'], $rows)
        );
        $mine = [];
        if ($rows) {
            $placeholders = implode(',', array_fill(0, count($rows), '?'));
            $seen = $this->store->all(
                'SELECT story_id FROM story_views WHERE user_id = ? AND story_id IN (' . $placeholders . ')',
                array_merge([(int) $user['id']], array_map(static fn (array $r): int => (int) $r['id'], $rows))
            );
            foreach ($seen as $s) {
                $mine[(int) $s['story_id']] = true;
            }
        }
        foreach ($rows as &$row) {
            $row['view_count'] = $counts[(int) $row['id']] ?? 0;
            $row['viewed_by_me'] = isset($mine[(int) $row['id']]);
        }
        unset($row);
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
        $style = strtolower(trim($this->str_param('style')));
        $allowedStyles = ['sunset', 'ocean', 'forest', 'grape', 'mono', 'candy'];
        if ($style !== '' && !in_array($style, $allowedStyles, true)) {
            $this->fail('style must be one of: ' . implode(', ', $allowedStyles) . '.', 422, 'invalid_style');
        }

        $story = $this->moment_model->createStory((int) $user['id'], $body, $media, $ttl, $style !== '' ? $style : null);
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

    public function story_view(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['id']);

        $story = $this->moment_model->findStory($this->int_param('id'));
        if (!$story || $story['deleted_at'] !== null || strtotime($story['expires_at']) < time()) {
            $this->fail('Story not found.', 404, 'not_found');
        }
        $authorId = (int) $story['user_id'];
        if ($authorId !== (int) $user['id'] && !$this->friend_model->areFriends((int) $user['id'], $authorId)) {
            $this->fail('Story not found.', 404, 'not_found');
        }
        if ($this->friend_model->isBlocked((int) $user['id'], $authorId)
            || $this->friend_model->isBlocked($authorId, (int) $user['id'])) {
            $this->fail('Story not found.', 404, 'not_found');
        }
        $this->moment_model->recordStoryView((int) $story['id'], (int) $user['id']);
        if ($authorId !== (int) $user['id']) {
            $this->realtime_model->push([$authorId], 'story_viewed', [
                'story_id' => (int) $story['id'],
                'user' => user_dto($user),
            ]);
        }
        $this->ok(['viewed' => true]);
    }

    public function story_views(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $story = $this->moment_model->findStory($this->int_param('id'));
        if (!$story) {
            $this->fail('Story not found.', 404, 'not_found');
        }
        if ((int) $story['user_id'] !== (int) $user['id']) {
            $this->fail('Only the author can see story viewers.', 403, 'forbidden');
        }
        $this->ok([
            'story_id' => (int) $story['id'],
            'view_count' => count($views = $this->moment_model->storyViews((int) $story['id'])),
            'viewers' => array_map(
                static fn (array $r): array => [
                    'id' => (int) $r['id'],
                    'username' => $r['username'] ?? null,
                    'display_name' => $r['display_name'] ?? null,
                    'avatar_url' => $r['avatar_url'] ?? null,
                    'gender' => $r['gender'] ?? null,
                    'viewed_at' => $r['viewed_at'] ?? null,
                ],
                $views
            ),
        ]);
    }

    private function assertCanSeeMoment(array $moment, int $viewerId): void
    {
        if (($moment['audience'] ?? 'friends') === 'private' && (int) $moment['user_id'] !== $viewerId) {
            $this->fail('Moment not found.', 404, 'not_found');
        }
    }
}
