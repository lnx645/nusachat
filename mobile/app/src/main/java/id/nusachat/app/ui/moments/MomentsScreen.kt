package id.nusachat.app.ui.moments
import id.nusachat.app.ui.components.NusaOutlinedButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaProgress

import android.net.Uri
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import id.nusachat.app.data.Moment
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.MomentSkeleton
import id.nusachat.app.ui.components.NusaActionSheet
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.SheetAction
import id.nusachat.app.ui.components.SkeletonRow
import id.nusachat.app.ui.components.formatMomentTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val moments by vm.moments.collectAsStateWithLifecycle()
    val loading by vm.momentsLoading.collectAsStateWithLifecycle()
    val hasMore by vm.momentsHasMore.collectAsStateWithLifecycle()
    val loadingMore by vm.momentsLoadingMore.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val me = session.user
    val commentsMomentId by vm.commentsMomentId.collectAsStateWithLifecycle()
    val comments by vm.comments.collectAsStateWithLifecycle()
    val commentsLoading by vm.commentsLoading.collectAsStateWithLifecycle()
    var showComposer by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NusaTopAppBar(
                title = { Text("Momen") },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    NusaIconButton(onClick = { showComposer = true }) {
                        Icon(painter = painterResource(id = NusaIcons.Camera), contentDescription = "Buat momen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "cover") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(188.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    Color(0xFF0B6B52),
                                    Color(0xFF10382D),
                                )
                            )
                        ),
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Photo),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = 26.dp)
                            .size(170.dp),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(66.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                NusaAvatar(
                                    name = me?.displayName ?: "?",
                                    url = me?.avatarUrl,
                                    size = 60.dp,
                                )
                            }
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = me?.displayName ?: "NusaChat",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "@${me?.username.orEmpty()}",
                                    color = Color.White.copy(alpha = 0.82f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Bagikan cerita kecil hari ini.",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (loading && moments.isEmpty()) {
                item(key = "loading") { MomentSkeleton() }
                item(key = "loading2") { MomentSkeleton() }
                item(key = "loading3") { MomentSkeleton() }
            } else if (moments.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = NusaIcons.Photo,
                        title = "Belum ada momen",
                        subtitle = "Bagikan foto atau cerita pertamamu agar teman bisa melihatnya.",
                    )
                }
            } else {
                items(moments, key = { it.id }) { moment ->
                    MomentItem(
                        moment = moment,
                        isMine = me?.id == moment.author.id,
                        onLike = { vm.toggleLike(moment) },
                        onComment = { vm.openComments(moment.id) },
                        onDelete = { vm.deleteMoment(moment) },
                    )
                }
                if (hasMore) {
                    item(key = "more") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            if (loadingMore) {
                                NusaProgress(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                NusaOutlinedButton(onClick = vm::loadMoreMoments) { Text("Muat momen lama") }
                            }
                        }
                    }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showComposer) {
        CreateMomentScreen(
            user = me,
            onBack = { showComposer = false },
            onPost = { text, uris ->
                showComposer = false
                vm.createMoment(text, uris)
            },
        )
    }

    if (commentsMomentId != null) {
        NusaBottomSheet(
            onDismissRequest = vm::closeComments,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            CommentsSheet(
                comments = comments,
                loading = commentsLoading,
                onSend = vm::addComment,
            )
        }
    }
}

@Composable
private fun MomentItem(
    moment: Moment,
    isMine: Boolean,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        NusaAvatar(
            name = moment.author.displayName,
            url = moment.author.avatarUrl,
            size = 42.dp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = moment.author.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (isMine) {
                    NusaIconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                        Icon(painter = painterResource(id = NusaIcons.More), contentDescription = "Opsi momen")
                    }
                }
            }
            if (moment.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = moment.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (moment.media.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MomentMedia(media = moment.media)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatMomentTime(moment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                NusaIconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = if (moment.likedByMe) NusaIcons.Like else NusaIcons.Like),
                        contentDescription = if (moment.likedByMe) "Batal suka" else "Suka",
                        tint = if (moment.likedByMe) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (moment.likeCount > 0) {
                    Text(
                        text = moment.likeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(8.dp))
                NusaIconButton(onClick = onComment, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = NusaIcons.ChatOutline),
                        contentDescription = "Komentar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                if (moment.commentCount > 0) {
                    Text(
                        text = moment.commentCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 67.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    if (menuOpen) {
        NusaActionSheet(
            title = moment.author.displayName,
            subtitle = "@${moment.author.username}",
            actions = listOf(
                SheetAction("Hapus momen", NusaIcons.Delete, danger = true) { menuOpen = false; confirmDelete = true }
            ),
            onDismiss = { menuOpen = false },
        )
    }
    if (confirmDelete) {
        NusaConfirmSheet(
            title = "Hapus momen?",
            text = "Momen yang dihapus tidak bisa dikembalikan.",
            confirmLabel = "Hapus",
            danger = true,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun MomentMedia(media: List<String>) {
    if (media.size == 1) {
        AsyncImage(
            model = media.first(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        media.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentsSheet(
    comments: List<id.nusachat.app.data.MomentComment>,
    loading: Boolean,
    onSend: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Komentar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        when {
            loading -> Column(modifier = Modifier.fillMaxWidth()) {
                repeat(3) { SkeletonRow(lines = 2) }
            }
            comments.isEmpty() -> Text(
                text = "Belum ada komentar. Jadilah yang pertama!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(comments, key = { it.id }) { comment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        NusaAvatar(
                            name = comment.user.displayName,
                            url = comment.user.avatarUrl,
                            size = 30.dp,
                        )
                        Column(modifier = Modifier.padding(start = 9.dp)) {
                            Text(
                                text = comment.user.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = comment.body,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Tulis komentar...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            NusaIconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Send),
                    contentDescription = "Kirim komentar",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
