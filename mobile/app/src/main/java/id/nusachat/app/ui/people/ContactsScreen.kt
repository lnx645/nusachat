package id.nusachat.app.ui.people
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaProgress
import id.nusachat.app.ui.components.NusaCheckbox
import id.nusachat.app.ui.components.NusaTextField

import android.Manifest
import androidx.compose.ui.res.painterResource
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.SkeletonList
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.NusaSearchField
import id.nusachat.app.ui.components.SectionLabel
import id.nusachat.app.ui.components.UnreadBadge
import id.nusachat.app.ui.components.UserRow

@Composable
fun ContactsScreen(
    vm: AppViewModel,
    onOpenFriends: () -> Unit,
    onOpenChatWith: (User) -> Unit,
) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val requests by vm.requests.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searchLoading by vm.searchLoading.collectAsStateWithLifecycle()
    val syncing by vm.contactsSyncing.collectAsStateWithLifecycle()
    val matched by vm.contactsMatched.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showCreateGroup by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<User?>(null) }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.syncPhoneContacts()
    }
    fun requestSync() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.syncPhoneContacts()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    val sortedFriends = friends.sortedBy { it.displayName.lowercase() }
    val filteredFriends = sortedFriends.filter {
        query.isBlank() ||
            it.displayName.contains(query, ignoreCase = true) ||
            it.username.contains(query, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Kontak",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${friends.size} teman • ${requests.incoming.size} permintaan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NusaIconButton(onClick = { showCreateGroup = true }) {
                Icon(painter = painterResource(id = NusaIcons.GroupAdd), contentDescription = "Grup baru")
            }
        }

        NusaSearchField(
            value = query,
            onValueChange = {
                query = it
                if (it.trim().length < 2) vm.clearSearch()
            },
            placeholder = "Cari teman atau pengguna",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            onSearch = { vm.searchUsers(query) },
        )

        if (query.trim().length >= 2) {
            SearchResults(
                vm = vm,
                query = query,
                results = searchResults,
                loading = searchLoading,
                onOpenChatWith = onOpenChatWith,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "new-friends") {
                    FunctionRow(
                        icon = NusaIcons.PersonAdd,
                        iconTint = Color(0xFFE39B1D),
                        iconBackground = Color(0xFFFFF2D9),
                        title = "Teman Baru",
                        subtitle = if (requests.incoming.isEmpty()) {
                            "Kelola permintaan pertemanan"
                        } else {
                            "${requests.incoming.size} permintaan menunggu"
                        },
                        badge = requests.incoming.size,
                        onClick = onOpenFriends,
                    )
                }
                item(key = "new-group") {
                    FunctionRow(
                        icon = NusaIcons.GroupAdd,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBackground = MaterialTheme.colorScheme.primaryContainer,
                        title = "Grup Baru",
                        subtitle = "Ngobrol bareng beberapa teman",
                        badge = 0,
                        onClick = { showCreateGroup = true },
                    )
                }
                item(key = "sync") {
                    FunctionRow(
                        icon = NusaIcons.Refresh,
                        iconTint = Color(0xFF0B6B52),
                        iconBackground = Color(0xFFDFF3EA),
                        title = if (syncing) "Menyinkronkan..." else "Sinkronkan Kontak HP",
                        subtitle = if (matched.isEmpty()) {
                            "Cari teman dari nomor HP"
                        } else {
                            "${matched.size} kontak memakai NusaChat"
                        },
                        badge = 0,
                        onClick = { requestSync() },
                    )
                }
                if (syncing) {
                    item(key = "syncing") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            NusaProgress(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (matched.isNotEmpty()) {
                    item(key = "matched-label") { SectionLabel("Dari kontak HP (${matched.size})") }
                    items(matched, key = { "matched-${it.id}" }) { user ->
                        UserRow(
                            user = user,
                            subtitle = "@${user.username}",
                            onClick = {
                                if ((user.friendStatus ?: "none") == "accepted") onOpenChatWith(user)
                            },
                            trailing = {
                                val status = user.friendStatus ?: "none"
                                when (status) {
                                    "accepted" -> NusaIconButton(onClick = { onOpenChatWith(user) }) {
                                        Icon(painter = painterResource(id = NusaIcons.Chat), contentDescription = "Chat")
                                    }
                                    "pending", "outgoing" -> Icon(painter = painterResource(id = NusaIcons.Time), contentDescription = null)
                                    else -> NusaIconButton(onClick = { vm.sendFriendRequest(user) }) {
                                        Icon(painter = painterResource(id = NusaIcons.PersonAdd), contentDescription = "Tambah")
                                    }
                                }
                            },
                        )
                    }
                }
                item(key = "friends-label") {
                    SectionLabel("Teman (${filteredFriends.size})")
                }
                if (filteredFriends.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = NusaIcons.PersonAdd,
                            title = if (friends.isEmpty()) "Belum ada teman" else "Tidak ditemukan",
                            subtitle = if (friends.isEmpty()) {
                                "Cari pengguna lewat kolom pencarian, lalu kirim permintaan pertemanan."
                            } else {
                                "Coba kata kunci lain."
                            },
                        )
                    }
                } else {
                    items(filteredFriends, key = { it.id }) { friend ->
                        UserRow(
                            user = friend,
                            subtitle = if (friend.online) "Online" else "@${friend.username}",
                            onClick = { onOpenChatWith(friend) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    NusaIconButton(onClick = { onOpenChatWith(friend) }) {
                                        Icon(
                                            painter = painterResource(id = NusaIcons.Chat),
                                            contentDescription = "Chat",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(21.dp),
                                        )
                                    }
                                    NusaIconButton(onClick = { removeTarget = friend }) {
                                        Icon(
                                            painter = painterResource(id = NusaIcons.Delete),
                                            contentDescription = "Hapus teman",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (showCreateGroup) {
        CreateGroupDialog(
            friends = sortedFriends,
            onDismiss = { showCreateGroup = false },
            onCreate = { title, ids ->
                showCreateGroup = false
                vm.createGroup(title, ids)
            },
        )
    }

    removeTarget?.let { target ->
        NusaConfirmSheet(
            title = "Hapus ${target.displayName}?",
            text = "Kamu tidak lagi berteman. Obrolan lama tetap tersimpan.",
            confirmLabel = "Hapus",
            danger = true,
            onConfirm = { vm.removeFriend(target); removeTarget = null },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun SearchResults(
    vm: AppViewModel,
    query: String,
    results: List<User>,
    loading: Boolean,
    onOpenChatWith: (User) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionLabel("Hasil pencarian untuk \"$query\"")
        when {
            loading -> SkeletonList(count = 4)
            results.isEmpty() -> EmptyState(
                icon = NusaIcons.PersonAdd,
                title = "Tidak ada pengguna",
                subtitle = "Coba kata kunci lain. Username minimal 2 karakter.",
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { user ->
                    val status = user.friendStatus ?: "none"
                    UserRow(
                        user = user,
                        subtitle = buildString {
                            append("@").append(user.username)
                            if (!user.bio.isNullOrBlank()) append(" • ").append(user.bio)
                        },
                        onClick = {
                            if (status == "accepted") onOpenChatWith(user)
                        },
                        trailing = {
                            when (status) {
                                "accepted" -> NusaIconButton(onClick = { onOpenChatWith(user) }) {
                                    Icon(
                                        painter = painterResource(id = NusaIcons.Chat),
                                        contentDescription = "Chat",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                                "pending" -> Icon(
                                    painter = painterResource(id = NusaIcons.Time),
                                    contentDescription = "Menunggu",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                "incoming" -> NusaTextButton(onClick = { vm.loadRequests() }) {
                                    Text("Undangan")
                                }
                                else -> NusaIconButton(onClick = { vm.sendFriendRequest(user) }) {
                                    Icon(
                                        painter = painterResource(id = NusaIcons.PersonAdd),
                                        contentDescription = "Tambah teman",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun FunctionRow(
    icon: Int,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    badge: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (badge > 0) {
            UnreadBadge(badge)
            Spacer(Modifier.size(8.dp))
        }
        Icon(
            painter = painterResource(id = NusaIcons.Next),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupDialog(
    friends: List<User>,
    onDismiss: () -> Unit,
    onCreate: (String, List<Long>) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }

    NusaBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Grup baru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            NusaTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Nama grup") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Pilih anggota",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (friends.isEmpty()) {
                Text(
                    text = "Tambahkan teman terlebih dahulu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(friends, key = { it.id }) { friend ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected.contains(friend.id)) {
                                        selected.remove(friend.id)
                                    } else {
                                        selected.add(friend.id)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NusaCheckbox(
                                checked = selected.contains(friend.id),
                                onCheckedChange = {
                                    if (selected.contains(friend.id)) {
                                        selected.remove(friend.id)
                                    } else {
                                        selected.add(friend.id)
                                    }
                                },
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(friend.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "@${friend.username}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            NusaButton(
                onClick = { onCreate(title.trim(), selected.toList()) },
                enabled = title.trim().length >= 2 && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Buat grup", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
