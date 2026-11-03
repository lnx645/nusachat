<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Contact_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    /**
     * Store contact hashes and return users whose phone hash matches.
     *
     * @param array $contacts list of {name, phone}
     * @return array matched users
     */
    public function sync(int $userId, array $contacts): array
    {
        $hashes = [];
        $now = now_iso();
        $this->store->begin();
        try {
            $count = 0;
            foreach ($contacts as $contact) {
                if ($count >= 2000) {
                    break;
                }
                $name = trim((string) ($contact['name'] ?? ''));
                $phone = normalize_phone((string) ($contact['phone'] ?? ''));
                if ($phone === null) {
                    continue;
                }
                $hash = phone_hash($phone);
                $hashes[$hash] = true;
                $existing = $this->store->one(
                    'SELECT id FROM contacts WHERE user_id = ? AND phone_hash = ?',
                    [$userId, $hash]
                );
                if ($existing) {
                    $this->store->update('contacts', ['name' => mb_substr($name, 0, 100), 'updated_at' => $now], 'id = ?', [(int) $existing['id']]);
                } else {
                    $this->store->insert('contacts', [
                        'user_id' => $userId,
                        'phone_hash' => $hash,
                        'name' => mb_substr($name, 0, 100),
                        'created_at' => $now,
                        'updated_at' => $now,
                    ]);
                }
                $count++;
            }
            $this->store->commit();
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }

        if (!$hashes) {
            return [];
        }

        $hashList = array_keys($hashes);
        $matched = [];
        foreach (array_chunk($hashList, 400) as $chunk) {
            $placeholders = implode(',', array_fill(0, count($chunk), '?'));
            $rows = $this->store->all(
                'SELECT * FROM users WHERE phone_hash IN (' . $placeholders . ') AND is_active = 1 AND id <> ?',
                array_merge($chunk, [$userId])
            );
            foreach ($rows as $row) {
                $matched[(int) $row['id']] = $row;
            }
        }

        foreach ($matched as $row) {
            $this->store->update(
                'contacts',
                ['matched_user_id' => (int) $row['id']],
                'user_id = ? AND phone_hash = ?',
                [$userId, $row['phone_hash']]
            );
        }

        return array_values($matched);
    }
}
