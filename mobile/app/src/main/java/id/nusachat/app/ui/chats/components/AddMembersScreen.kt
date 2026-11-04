package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.nusachat.app.data.User
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaCheckbox
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaSearchField
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Halaman tambah anggota grup ala LINE — full screen, bukan sheet.
 * Reusable: dipakai dari info grup dan buat grup baru.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddMembersScreen(
    friends: List<User>,
    existingIds: Set<Long>,
    onBack: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }

    val selectable = remember(friends, existingIds) {
        friends.filterNot { existingIds.contains(it.id) }
    }
    val filtered = remember(selectable, query) {
        if (query.isBlank()) selectable
        else selectable.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true)
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy {
            val c = it.displayName.trim().firstOrNull()?.uppercaseChar()
            when {
                c == null -> "#"
                c.isLetter() -> c.toString()
                else -> "#"
            }
        }.toSortedMap(compareBy { if (it == "#") "ZZZ" else it })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NusaTopAppBar(
            title = {
                Column {
                    Text("Tambah anggota")
                    Text(
                        text = if (selectable.isEmpty()) "Tidak ada teman tersedia"
                        else "${filtered.size} teman • ${selected.size} dipilih",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                NusaIconButton(onClick = onBack) {
                    Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                }
            },
        )

        // Chip terpilih ala LINE (scroll horizontal)
        if (selected.isNotEmpty()) {
            val chosen = selectable.filter { selected.contains(it.id) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chosen.take(6).forEach { user ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                        Box {
                            NusaAvatar(name = user.displayName, url = user.avatarUrl, gender = user.gender, seed = user.id, size = 48.dp)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                                    .clickable { selected = selected - user.id },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Close),
                                    contentDescription = "Hapus",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                        Text(
                            text = user.displayName.split(" ").firstOrNull() ?: user.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (chosen.size > 6) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+${chosen.size - 6}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.7.dp)
        }

        NusaSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Cari teman",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )

        if (selectable.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Semua teman sudah menjadi anggota.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tidak ditemukan.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                grouped.forEach { (initial, list) ->
                    stickyHeader(key = "h-$initial") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(list, key = { it.id }) { friend ->
                        val isSelected = selected.contains(friend.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (isSelected) selected - friend.id else selected + friend.id
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NusaAvatar(name = friend.displayName, url = friend.avatarUrl, gender = friend.gender, seed = friend.id, size = 44.dp)
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(text = friend.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(text = "@${friend.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            NusaCheckbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    selected = if (isSelected) selected - friend.id else selected + friend.id
                                },
                            )
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    NusaButton(
                        onClick = { onConfirm(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text("Tambah (${selected.size})")
                    }
                }
            }
        }
    }
}
