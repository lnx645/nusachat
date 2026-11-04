package id.nusachat.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaOutlinedButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.formatMomentTime

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PublicProfileScreen(
    user: User,
    vm: AppViewModel,
    onBack: () -> Unit,
    onChat: () -> Unit,
) {
    val moments by vm.moments.collectAsStateWithLifecycle()
    val sessionUser by vm.sessionState.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val requests by vm.requests.collectAsStateWithLifecycle()
    val isSelf = sessionUser.user?.id == user.id
    val outgoingIds = androidx.compose.runtime.remember(requests) { requests.outgoing.map { it.user.id }.toSet() }
    val incomingIds = androidx.compose.runtime.remember(requests) { requests.incoming.map { it.user.id }.toSet() }
    val isActuallyFriend = friends.any { it.id == user.id }
    val friendStatus = when {
        isActuallyFriend -> "accepted"
        outgoingIds.contains(user.id) -> "pending"
        incomingIds.contains(user.id) -> "pending"
        else -> user.friendStatus ?: "none"
    }
    val requestDirection = when {
        isActuallyFriend -> null
        outgoingIds.contains(user.id) -> "outgoing"
        incomingIds.contains(user.id) -> "incoming"
        else -> user.requestDirection
    }

    LaunchedEffect(user.id) {
        if (moments.isEmpty()) {
            runCatching { vm.refreshMoments() }
        }
    }

    val userMoments = moments.filter { it.author.id == user.id }
    var selectedMoment by remember { mutableStateOf<id.nusachat.app.data.Moment?>(null) }

    if (selectedMoment != null) {
        val detail = selectedMoment!!
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            NusaTopAppBar(
                title = { Text("Momen") },
                navigationIcon = {
                    NusaIconButton(onClick = { selectedMoment = null }) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
            )
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                item(key = "detail-${detail.id}") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NusaAvatar(name = detail.author.displayName, url = detail.author.avatarUrl, gender = detail.author.gender, seed = detail.author.id, size = 40.dp)
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(detail.author.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF576B95))
                                Text(formatMomentTime(detail.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (detail.body.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(detail.body, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (detail.media.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            val first = detail.media.first()
                            AsyncImage(
                                model = first,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            if (detail.media.size > 1) {
                                Spacer(Modifier.height(6.dp))
                                Text("${detail.media.size} foto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painter = painterResource(id = NusaIcons.Like), contentDescription = null, tint = if (detail.likedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${detail.likeCount} suka • ${detail.commentCount} komentar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NusaTopAppBar(
            title = { Text(user.displayName) },
            navigationIcon = {
                NusaIconButton(onClick = onBack) {
                    Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NusaAvatar(name = user.displayName, url = user.avatarUrl, gender = user.gender, seed = user.id, size = 88.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!user.bio.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = user.bio!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (!user.gender.isNullOrBlank() || !user.birthday.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        user.gender?.takeIf { it.isNotBlank() }?.let {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    when (it) {
                                        "male" -> "Laki-laki"
                                        "female" -> "Perempuan"
                                        else -> it
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                        user.birthday?.takeIf { it.isNotBlank() }?.let {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (!isSelf) {
                    var localStatus by androidx.compose.runtime.remember(user.id, friendStatus) { androidx.compose.runtime.mutableStateOf(friendStatus) }
                    var localDirection by androidx.compose.runtime.remember(user.id, requestDirection) { androidx.compose.runtime.mutableStateOf(requestDirection) }
                    var showUnfriend by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var showCancel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var showBlock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var blockedLocal by androidx.compose.runtime.remember(user.id, user.blockedByMe) { androidx.compose.runtime.mutableStateOf(user.blockedByMe) }
                    androidx.compose.runtime.LaunchedEffect(friendStatus, requestDirection) {
                        localStatus = friendStatus
                        localDirection = requestDirection
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            localStatus == "accepted" -> {
                                NusaButton(onClick = onChat, modifier = Modifier.weight(1f)) {
                                    Icon(painter = painterResource(id = NusaIcons.Chat), contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Chat")
                                }
                                NusaOutlinedButton(
                                    onClick = { showUnfriend = true },
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                ) {
                                    Icon(painter = painterResource(id = NusaIcons.Delete), contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Hapus teman")
                                }
                            }
                            localStatus == "pending" && localDirection == "outgoing" -> {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(id = NusaIcons.Time),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Menunggu persetujuan",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "Batalkan",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .clickable { showCancel = true }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            localStatus == "pending" && localDirection == "incoming" -> {
                                NusaButton(onClick = {
                                    user.requestId?.let { rid -> vm.respondToRequest(id.nusachat.app.data.FriendRequest(rid, "pending", "", user, true), true); localStatus = "accepted" }
                                }, modifier = Modifier.weight(1f)) {
                                    Text("Terima")
                                }
                                NusaOutlinedButton(onClick = {
                                    user.requestId?.let { rid -> vm.respondToRequest(id.nusachat.app.data.FriendRequest(rid, "pending", "", user, true), false); localStatus = "none" }
                                }, modifier = Modifier.weight(1f)) {
                                    Text("Tolak")
                                }
                            }
                            else -> NusaButton(onClick = {
                                vm.sendFriendRequest(user)
                                localStatus = "pending"
                                localDirection = "outgoing"
                            }, modifier = Modifier.weight(1f)) {
                                Icon(painter = painterResource(id = NusaIcons.PersonAdd), contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Tambah teman")
                            }
                        }
                    }
                    if (showUnfriend) {
                        id.nusachat.app.ui.components.NusaConfirmSheet(
                            title = "Hapus teman?",
                            text = "Kamu dan ${user.displayName} tidak lagi berteman. Chat lama tetap ada.",
                            confirmLabel = "Hapus",
                            danger = true,
                            onConfirm = {
                                showUnfriend = false
                                vm.removeFriend(user)
                                localStatus = "none"
                            },
                            onDismiss = { showUnfriend = false },
                        )
                    }
                    if (showCancel) {
                        id.nusachat.app.ui.components.NusaConfirmSheet(
                            title = "Batalkan permintaan?",
                            text = "Permintaan pertemanan ke ${user.displayName} akan dibatalkan.",
                            confirmLabel = "Batalkan",
                            danger = true,
                            onConfirm = {
                                showCancel = false
                                vm.removeFriend(user)
                                localStatus = "none"
                                localDirection = null
                            },
                            onDismiss = { showCancel = false },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    NusaOutlinedButton(
                        onClick = {
                            if (blockedLocal) {
                                vm.unblockUser(user)
                                blockedLocal = false
                            } else {
                                showBlock = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Text(if (blockedLocal) "Buka blokir" else "Blokir")
                    }
                    if (showBlock) {
                        id.nusachat.app.ui.components.NusaConfirmSheet(
                            title = "Blokir ${user.displayName}?",
                            text = "Kamu tidak menerima pesan atau panggilan darinya. Chat lama tetap ada.",
                            confirmLabel = "Blokir",
                            danger = true,
                            onConfirm = {
                                showBlock = false
                                vm.blockUser(user)
                                blockedLocal = true
                            },
                            onDismiss = { showBlock = false },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("${user.friendCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Teman", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("${userMoments.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Momen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        if (user.online) "Online" else "Offline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (user.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (friendStatus == "accepted") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(id = NusaIcons.Check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Berteman", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Momen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${userMoments.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (userMoments.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(painter = painterResource(id = NusaIcons.Photo), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Belum ada momen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(((userMoments.size + 2) / 3 * 110 + 6).dp)
                                .padding(horizontal = 2.dp),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            userScrollEnabled = false,
                        ) {
                            items(userMoments, key = { it.id }) { moment ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { selectedMoment = moment },
                                ) {
                                    if (moment.media.isNotEmpty()) {
                                        AsyncImage(
                                            model = moment.media.first(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                moment.body.take(60),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 4,
                                            )
                                        }
                                    }
                                    if (moment.likeCount > 0 || moment.commentCount > 0) {
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(topEnd = 6.dp))
                                                .padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (moment.likeCount > 0) {
                                                Icon(painter = painterResource(id = NusaIcons.Like), contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(10.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Text("${moment.likeCount}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = androidx.compose.ui.graphics.Color.White)
                                            }
                                            if (moment.likeCount > 0 && moment.commentCount > 0) {
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            if (moment.commentCount > 0) {
                                                Icon(painter = painterResource(id = NusaIcons.ChatOutline), contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(10.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Text("${moment.commentCount}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = androidx.compose.ui.graphics.Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}
