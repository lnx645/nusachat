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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
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
import id.nusachat.app.data.Story
import id.nusachat.app.data.User
import id.nusachat.app.data.isVideoUrl
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
    var commentMoment by remember(moments, commentsMomentId) {
        mutableStateOf(moments.firstOrNull { it.id == commentsMomentId })
    }
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var shareTarget by remember { mutableStateOf<Moment?>(null) }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    var showStoryComposer by remember { mutableStateOf(false) }
    var storyViewer by remember { mutableStateOf<List<Story>?>(null) }
    var storyStartIndex by remember { mutableStateOf(0) }

    if (commentsMomentId != null && commentMoment != null) {
        MomentCommentsScreen(
            moment = commentMoment!!,
            comments = comments,
            loading = commentsLoading,
            onBack = vm::closeComments,
            onSend = { text, parentId -> vm.addComment(text, parentId) },
        )
        return
    }

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
                // Cover ala WeChat: foto sampul + avatar bundar di tepi — banner default pakai warna aplikasi
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                )
                            )
                        ),
                ) {
                    // pattern dot putih halus di atas hijau aplikasi
                    val bannerDot = Color.White.copy(alpha = 0.13f)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stepPx = 20.dp.toPx()
                        val rPx = 1.4.dp.toPx()
                        var y = 0f
                        while (y < size.height) {
                            var x = (y / stepPx % 2) * stepPx / 2
                            while (x < size.width) {
                                drawCircle(color = bannerDot, radius = rPx, center = androidx.compose.ui.geometry.Offset(x, y))
                                x += stepPx
                            }
                            y += stepPx
                        }
                    }
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(id = NusaIcons.Photo),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Momen",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 0.6.sp,
                        )
                    }
                    // nama + avatar di kanan bawah (WeChat style) — nama di dalam banner, pakai shadow agar jelas
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(y = 28.dp)
                            .padding(end = 16.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(end = 10.dp, bottom = 32.dp),
                        ) {
                            Text(
                                text = me?.displayName ?: "NusaChat",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.45f),
                                        offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                        blurRadius = 4f,
                                    )
                                ),
                            )
                            Text(
                                text = "@${me?.username.orEmpty()}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.35f),
                                        offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                        blurRadius = 3f,
                                    )
                                ),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            NusaAvatar(
                                name = me?.displayName ?: "?",
                                url = me?.avatarUrl, gender = me?.gender, seed = me?.id,
                                size = 70.dp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(38.dp))
            }

            item(key = "stories") {
                StoryStrip(
                    stories = stories,
                    me = me,
                    onCreate = { showStoryComposer = true },
                    onOpen = { list, index ->
                        storyViewer = list
                        storyStartIndex = index
                    },
                )
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
                        onShare = { shareTarget = moment },
                        onReshare = { vm.reshareMoment(moment) },
                        onImageClick = { idx -> viewer = moment.media to idx },
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

    viewer?.let { (media, idx) ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewer = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            MomentImageViewer(
                media = media,
                startIndex = idx,
                onDismiss = { viewer = null },
            )
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

    if (showStoryComposer) {
        CreateStoryScreen(
            user = me,
            onBack = { showStoryComposer = false },
            onPost = { text, uris ->
                showStoryComposer = false
                vm.createStory(text, uris)
            },
        )
    }

    storyViewer?.let { list ->
        StoryViewer(
            stories = list,
            startIndex = storyStartIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
            meId = me?.id ?: 0L,
            onDelete = { vm.deleteStory(it) },
            onDismiss = { storyViewer = null },
        )
    }

    shareTarget?.let { target ->
        NusaBottomSheet(onDismissRequest = { shareTarget = null }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = "Bagikan momen ke chat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.shareMomentToChat(target, conv.id)
                                    shareTarget = null
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NusaAvatar(
                                name = conv.displayTitle,
                                url = conv.avatarUrl,
                                size = 40.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = conv.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
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
    onShare: () -> Unit = {},
    onReshare: () -> Unit = {},
    onImageClick: (Int) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            NusaAvatar(
                name = moment.author.displayName,
                url = moment.author.avatarUrl, gender = moment.author.gender, seed = moment.author.id,
                size = 42.dp,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = moment.author.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF576B95),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMomentTime(moment.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NusaIconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painter = painterResource(id = NusaIcons.More),
                            contentDescription = "Opsi",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = "@${moment.author.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                moment.reshared?.let { info ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "↻ ${info.author?.displayName ?: "Momen asli"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (moment.body.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = moment.body,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (moment.media.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MomentMedia(
                        media = moment.media,
                        onImageClick = onImageClick,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Bar aksi ala WeChat: pill abu dengan like & comment
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // bubbles like/comment count
                    if (moment.likeCount > 0 || moment.commentCount > 0) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (moment.likeCount > 0) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Like),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${moment.likeCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (moment.commentCount > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            if (moment.commentCount > 0) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.ChatOutline),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${moment.commentCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable(onClick = onLike),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Like),
                                    contentDescription = if (moment.likedByMe) "Batal suka" else "Suka",
                                    tint = if (moment.likedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (moment.likedByMe) "Liked" else "Like",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (moment.likedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable(onClick = onComment),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.ChatOutline),
                                    contentDescription = "Komentar",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Comment",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 0.6.dp,
    )
    if (menuOpen) {
        val shareActions = listOf(
            SheetAction("Bagikan ke chat", NusaIcons.Forward) { menuOpen = false; onShare() },
            SheetAction("Posting ulang ke momen", NusaIcons.Refresh) { menuOpen = false; onReshare() },
        )
        NusaActionSheet(
            title = moment.author.displayName,
            subtitle = "@${moment.author.username}",
            actions = shareActions + if (isMine) {
                listOf(SheetAction("Hapus momen", NusaIcons.Delete, danger = true) { menuOpen = false; confirmDelete = true })
            } else {
                emptyList()
            },
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
private fun MomentMedia(
    media: List<String>,
    onImageClick: (Int) -> Unit = {},
) {
    val chipShape = RoundedCornerShape(8.dp)
    when (media.size) {
        1 -> MomentThumb(
            url = media.first(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            onClick = { onImageClick(0) },
        )
        2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            media.forEachIndexed { idx, url ->
                MomentThumb(
                    url = url,
                    modifier = Modifier
                        .weight(1f)
                        .height(180.dp)
                        .clip(chipShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { onImageClick(idx) },
                )
            }
        }
        4 -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            media.chunked(2).forEachIndexed { rIdx, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEachIndexed { cIdx, url ->
                        val idx = rIdx * 2 + cIdx
                        MomentThumb(
                            url = url,
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp)
                                .clip(chipShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            onClick = { onImageClick(idx) },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            media.chunked(3).forEachIndexed { rIdx, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEachIndexed { cIdx, url ->
                        val idx = rIdx * 3 + cIdx
                        MomentThumb(
                            url = url,
                            modifier = Modifier
                                .size(108.dp)
                                .clip(chipShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            onClick = { onImageClick(idx) },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.size(108.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MomentThumb(
    url: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    if (!isVideoUrl(url)) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clickable { onClick() },
        )
        return
    }
    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.Play),
                contentDescription = "Putar video",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun MomentVideoPlayer(
    url: String,
    autoPlay: Boolean,
    modifier: Modifier = Modifier,
) {
    var playing by remember(url) { mutableStateOf(autoPlay) }
    androidx.compose.runtime.LaunchedEffect(url, autoPlay) {
        playing = autoPlay
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(android.net.Uri.parse(url))
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        if (playing) start()
                    }
                }
            },
            update = { view ->
                if (playing) {
                    if (!view.isPlaying) view.start()
                } else {
                    view.pause()
                }
            },
            modifier = Modifier.matchParentSize(),
            onRelease = { view -> view.stopPlayback() },
        )
        if (!playing) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { playing = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Play),
                    contentDescription = "Putar",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { playing = false },
            )
        }
    }
}

@Composable
private fun MomentImageViewer(
    media: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(startIndex) }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = startIndex, pageCount = { media.size })
    // sinkron index
    androidx.compose.runtime.LaunchedEffect(pager.currentPage) { current = pager.currentPage }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val url = media[page]
                if (isVideoUrl(url)) {
                    MomentVideoPlayer(
                        url = url,
                        autoPlay = page == current,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
        if (media.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                media.forEachIndexed { i, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (i == current) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (i == current) Color.White else Color.White.copy(alpha = 0.4f)),
                    )
                }
            }
        }
        NusaIconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = "Tutup", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(
            "${current + 1} / ${media.size}",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
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
                            url = comment.user.avatarUrl, gender = comment.user.gender, seed = comment.user.id,
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
