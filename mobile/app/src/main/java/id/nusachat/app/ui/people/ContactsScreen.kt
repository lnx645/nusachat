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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import kotlinx.coroutines.launch
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.NusaActionSheet
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.NusaDialog
import id.nusachat.app.ui.components.NusaSearchField
import id.nusachat.app.ui.components.ProfileLinkSheet
import id.nusachat.app.ui.components.SheetAction
import id.nusachat.app.ui.components.SectionLabel
import id.nusachat.app.ui.components.SkeletonList
import id.nusachat.app.ui.components.UnreadBadge
import id.nusachat.app.ui.components.UserRow

@Composable
fun ContactsScreen(
    vm: AppViewModel,
    onOpenFriends: () -> Unit,
    onOpenChatWith: (User) -> Unit,
    onOpenProfile: () -> Unit = {},
    onScanQr: () -> Unit = {},
    scanSeed: String? = null,
    onScanConsumed: () -> Unit = {},
) {
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val me = session.user
    val friends by vm.friends.collectAsStateWithLifecycle()
    val requests by vm.requests.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searchLoading by vm.searchLoading.collectAsStateWithLifecycle()
    val syncing by vm.contactsSyncing.collectAsStateWithLifecycle()
    val matched by vm.contactsMatched.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadBlocked() }
    var showCreateGroup by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<User?>(null) }
    var friendMenu by remember { mutableStateOf<User?>(null) }
    var remarkTarget by remember { mutableStateOf<User?>(null) }
    var greetTarget by remember { mutableStateOf<User?>(null) }
    var showMyLink by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(scanSeed) {
        if (!scanSeed.isNullOrBlank()) {
            query = scanSeed
            vm.searchUsers(parseProfileLink(scanSeed) ?: scanSeed)
            onScanConsumed()
        }
    }
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

    // Header sebaris (bukan TopAppBar) — halaman ini di dalam Scaffold utama,
    // TopAppBar bersarang bikin app bar dobel / ruang kosong di atas.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Teman",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "${friends.size} teman",
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
                placeholder = "Cari teman / tempel tautan",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onSearch = {
                    val linkUser = parseProfileLink(query)
                    vm.searchUsers(linkUser ?: query)
                },
            )
            parseProfileLink(query)?.let { linked ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.searchUsers(linked) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Link),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Tambah @$linked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Profil diri ala LINE Home.
            if (me != null && query.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProfile)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    id.nusachat.app.ui.components.NusaAvatar(
                        name = me.displayName,
                        url = me.avatarUrl, gender = me.gender, seed = me.id,
                        size = 54.dp,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            text = me.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            text = me.bio?.takeIf { it.isNotBlank() } ?: "@${me.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Icon(
                        painter = painterResource(id = NusaIcons.Next),
                        contentDescription = "Profil saya",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.7.dp,
                )
            }

            if (query.trim().length >= 2) {
                SearchResults(
                    vm = vm,
                    query = query,
                    results = searchResults,
                    loading = searchLoading,
                    onOpenChatWith = onOpenChatWith,
                    onAdd = { greetTarget = it },
                )
            } else {
                val grouped = remember(filteredFriends) {
                    filteredFriends.groupBy {
                        val c = it.displayName.trim().firstOrNull()?.uppercaseChar()
                        when {
                            c == null -> "#"
                            c.isLetter() -> c.toString()
                            else -> "#"
                        }
                    }.toSortedMap(compareBy { if (it == "#") "ZZZ" else it })
                }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val headerIndices = remember(grouped, syncing, matched.size) {
                    val map = mutableMapOf<String, Int>()
                    var i = 1 // kartu fungsi
                    if (syncing) i++
                    if (matched.isNotEmpty()) i += 1 + matched.size
                    grouped.forEach { (initial, list) ->
                        map[initial] = i
                        i += 1 + list.size
                    }
                    map
                }
                Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp, end = 14.dp),
                ) {
                    item(key = "functions") {
                        // Section flat full-width seperti info grup — tanpa kartu.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            Column {
                                FunctionRow(
                                    icon = NusaIcons.PersonAdd,
                                    title = "Teman Baru",
                                    subtitle = if (requests.incoming.isEmpty()) "Kelola permintaan" else "${requests.incoming.size} permintaan",
                                    badge = requests.incoming.size,
                                    onClick = onOpenFriends,
                                )
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.7.dp,
                                )
                                FunctionRow(
                                    icon = NusaIcons.GroupAdd,
                                    title = "Grup Baru",
                                    subtitle = "Buat grup dengan teman",
                                    badge = 0,
                                    onClick = { showCreateGroup = true },
                                )
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.7.dp,
                                )
                                FunctionRow(
                                    icon = NusaIcons.Refresh,
                                    title = if (syncing) "Menyinkronkan..." else "Sinkronkan Kontak",
                                    subtitle = if (matched.isEmpty()) "Cari teman dari HP" else "${matched.size} kontak ditemukan",
                                    badge = 0,
                                    onClick = { requestSync() },
                                )
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.7.dp,
                                )
                                FunctionRow(
                                    icon = NusaIcons.Qr,
                                    title = "Tautanku",
                                    subtitle = "Bagikan tautan profilmu",
                                    badge = 0,
                                    onClick = { showMyLink = true },
                                )
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.7.dp,
                                )
                                FunctionRow(
                                    icon = NusaIcons.Scan,
                                    title = "Pindai kode QR",
                                    subtitle = "Tambah teman lewat kamera",
                                    badge = 0,
                                    onClick = onScanQr,
                                )
                            }
                        }
                    }
                    if (blocked.isNotEmpty() && query.isBlank()) {
                        item(key = "blocked-label") { SectionLabel("Diblokir • ${blocked.size}") }
                        items(blocked, key = { "blocked-${it.id}" }) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                id.nusachat.app.ui.components.NusaAvatar(
                                    name = user.displayName,
                                    url = user.avatarUrl, gender = user.gender, seed = user.id,
                                    size = 44.dp,
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp),
                                ) {
                                    Text(
                                        text = user.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "@${user.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                NusaTextButton(onClick = { vm.unblockUser(user) }) {
                                    Text("Buka blokir")
                                }
                            }
                        }
                    }
                    if (syncing) {
                        item(key = "syncing") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                NusaProgress(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    if (matched.isNotEmpty()) {
                        item(key = "matched-label") { SectionLabel("Dari kontak HP • ${matched.size}") }
                        items(matched, key = { "matched-${it.id}" }) { user ->
                            val status = user.friendStatus ?: "none"
                            UserRow(
                                user = user,
                                subtitle = user.bio?.takeIf { it.isNotBlank() } ?: "@${user.username}",
                                onClick = {
                                    if (status == "accepted") onOpenChatWith(user)
                                },
                                trailing = {
                                    when (status) {
                                        "accepted" -> NusaIconButton(onClick = { onOpenChatWith(user) }) {
                                            Icon(painter = painterResource(id = NusaIcons.Chat), contentDescription = "Chat", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                        "pending", "outgoing" -> Icon(painter = painterResource(id = NusaIcons.Time), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        else -> NusaIconButton(onClick = { vm.sendFriendRequest(user) }) {
                                            Icon(painter = painterResource(id = NusaIcons.PersonAdd), contentDescription = "Tambah", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (filteredFriends.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = NusaIcons.PersonAdd,
                                title = if (friends.isEmpty()) "Belum ada teman" else "Tidak ditemukan",
                                subtitle = if (friends.isEmpty()) "Cari pengguna lewat pencarian di atas." else "Coba kata kunci lain.",
                            )
                        }
                    } else {
                        grouped.forEach { (initial, list) ->
                            stickyHeader(key = "header-$initial") {
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
                                ContactFriendRow(
                                    user = friend,
                                    onClick = { onOpenChatWith(friend) },
                                    onLongClick = { friendMenu = friend },
                                )
                            }
                        }
                    }
                }
                // Indeks alfabet ala LINE di tepi kanan.
                if (grouped.size > 1) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        grouped.keys.forEach { letter ->
                            Box(
                                modifier = Modifier
                                    .size(width = 18.dp, height = 18.dp)
                                    .clickable {
                                        scope.launch {
                                            headerIndices[letter]?.let {
                                                listState.animateScrollToItem(it)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = letter,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
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

    friendMenu?.let { friend ->
        NusaActionSheet(
            title = friend.familiarName,
            subtitle = "@${friend.username}",
            actions = listOf(
                SheetAction("Chat", NusaIcons.Chat) { friendMenu = null; onOpenChatWith(friend) },
                SheetAction("Nama panggilan", NusaIcons.Edit) { friendMenu = null; remarkTarget = friend },
                SheetAction("Hapus teman", NusaIcons.Delete, danger = true) { friendMenu = null; removeTarget = friend },
                SheetAction("Blokir", NusaIcons.Flag, danger = true) { vm.blockUser(friend); friendMenu = null },
            ),
            onDismiss = { friendMenu = null },
        )
    }

    remarkTarget?.let { friend ->
        RemarkDialog(
            user = friend,
            onConfirm = { vm.setRemark(friend.id, it); remarkTarget = null },
            onDismiss = { remarkTarget = null },
        )
    }

    greetTarget?.let { user ->
        GreetingSheet(
            user = user,
            onSend = { vm.sendFriendRequestWithMessage(user, it); greetTarget = null },
            onDismiss = { greetTarget = null },
        )
    }

    if (showMyLink && me != null) {
        ProfileLinkSheet(
            username = me.username,
            displayName = me.displayName,
            onCopy = {
                clipboard.setText(AnnotatedString("nusachat://user/${me.username}"))
                showMyLink = false
            },
            onShare = { vm.shareText("Tambahkan aku di NusaChat: nusachat://user/${me.username}", "Bagikan profil") },
            onDismiss = { showMyLink = false },
        )
    }
}

/** Ekstrak username dari tautan profil nusachat://user/<nama>. */
private fun parseProfileLink(text: String): String? {
    val match = Regex("nusachat://user/([A-Za-z0-9_.]{2,20})").find(text.trim())
    return match?.groupValues?.get(1)
}

@Composable
private fun RemarkDialog(user: User, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember(user.id) { mutableStateOf(user.remark.orEmpty()) }
    NusaDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nama panggilan") },
        text = {
            Column {
                Text(
                    "Hanya terlihat olehmu untuk ${user.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NusaTextField(
                    value = text,
                    onValueChange = { if (it.length <= 40) text = it },
                    placeholder = { Text("cth. Bestie SMA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            NusaTextButton(onClick = { onConfirm(text.trim().ifBlank { null }) }) { Text("Simpan") }
        },
        dismissButton = {
            NusaTextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GreetingSheet(user: User, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(user.id) { mutableStateOf("") }
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                "Tambah ${user.displayName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Sertakan sapaan agar dikenali (opsional).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            NusaTextField(
                value = text,
                onValueChange = { if (it.length <= 160) text = it },
                placeholder = { Text("cth. Halo, aku Budi dari kantor") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NusaButton(
                onClick = { onSend(text.trim()) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Kirim permintaan", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SearchResults(
    vm: AppViewModel,
    query: String,
    results: List<User>,
    loading: Boolean,
    onOpenChatWith: (User) -> Unit,
    onAdd: (User) -> Unit = { vm.sendFriendRequest(it) },
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
                                else -> NusaIconButton(onClick = { onAdd(user) }) {
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

/** Baris teman ala LINE: avatar, nama, @username, status message bubble di kanan. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactFriendRow(user: User, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            id.nusachat.app.ui.components.NusaAvatar(
                name = user.displayName,
                url = user.avatarUrl, gender = user.gender, seed = user.id,
                size = 50.dp,
            )
            if (user.online) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(id.nusachat.app.ui.components.NusaOnlineGreen),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = user.familiarName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = if (user.remark.isNullOrBlank()) "@${user.username}" else "${user.displayName} • @${user.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        user.bio?.takeIf { it.isNotBlank() }?.let { bio ->
            Box(
                modifier = Modifier
                    .widthIn(max = 108.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = bio,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FunctionRow(
    icon: Int,
    title: String,
    subtitle: String,
    badge: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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
            modifier = Modifier.size(18.dp),
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
