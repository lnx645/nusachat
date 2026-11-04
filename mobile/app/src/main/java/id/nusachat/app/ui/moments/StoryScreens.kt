package id.nusachat.app.ui.moments

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import id.nusachat.app.data.Story
import id.nusachat.app.data.User
import id.nusachat.app.data.isVideoUrl
import id.nusachat.app.ui.components.MediaPickerScreen
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.theme.NusaIcons

@Composable
fun StoryStrip(
    stories: List<Story>,
    me: User?,
    onCreate: () -> Unit,
    onOpen: (List<Story>, Int) -> Unit,
) {
    if (stories.isEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable { onCreate() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Story",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Bagikan momen 24 jam ke teman",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val grouped = stories.groupBy { it.author.id }
    val myStories = me?.let { grouped[it.id].orEmpty() }.orEmpty()
    val others = grouped.filterKeys { it != me?.id }.values.toList()
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "pad-start") { Spacer(Modifier.width(4.dp)) }
        item(key = "mine") {
            StoryTile(
                label = "Story Anda",
                avatar = { NusaAvatar(name = me?.displayName ?: "?", url = me?.avatarUrl, gender = me?.gender, seed = me?.id, size = 56.dp) },
                hasUnseen = myStories.isNotEmpty(),
                onClick = { if (myStories.isNotEmpty()) onOpen(myStories, 0) else onCreate() },
            )
        }
        items(others, key = { it.firstOrNull()?.author?.id ?: -1 }) { group ->
            val author = group.first().author
            StoryTile(
                label = author.displayName,
                avatar = { NusaAvatar(name = author.displayName, url = author.avatarUrl, gender = author.gender, seed = author.id, size = 56.dp) },
                hasUnseen = true,
                onClick = { onOpen(group, 0) },
            )
        }
        item(key = "pad-end") { Spacer(Modifier.width(4.dp)) }
    }
}

@Composable
private fun StoryTile(
    label: String,
    avatar: @Composable () -> Unit,
    hasUnseen: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .then(
                    if (hasUnseen) {
                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    }
                )
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            avatar()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StoryViewer(
    stories: List<Story>,
    startIndex: Int,
    meId: Long,
    onDelete: (Story) -> Unit,
    onDismiss: () -> Unit,
    onTrackView: (Story) -> Unit = {},
    onShowViewers: (Story) -> Unit = {},
    onReply: (Story) -> Unit = {},
) {
    if (stories.isEmpty()) {
        onDismiss()
        return
    }
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, stories.size - 1),
        pageCount = { stories.size },
    )
    var current by remember { mutableStateOf(startIndex.coerceIn(0, stories.size - 1)) }
    androidx.compose.runtime.LaunchedEffect(pager.currentPage) { current = pager.currentPage }
    val story = stories[current]
    androidx.compose.runtime.LaunchedEffect(story.id) {
        if (story.author.id != meId) onTrackView(story)
    }

    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = stories[page]
                val url = item.media.firstOrNull()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (url == null) Modifier.background(StoryStyles.brushFor(item.style)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (url != null && isVideoUrl(url)) {
                        MomentVideoPlayer(
                            url = url,
                            autoPlay = page == current,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (url != null) {
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = item.body,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NusaAvatar(
                    name = story.author.displayName,
                    url = story.author.avatarUrl, gender = story.author.gender, seed = story.author.id,
                    size = 36.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = story.author.displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = id.nusachat.app.ui.components.formatMomentTime(story.createdAt),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (story.author.id == meId) {
                    NusaIconButton(onClick = { onDelete(story); onDismiss() }) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Delete),
                            contentDescription = "Hapus story",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                NusaIconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Close),
                        contentDescription = "Tutup",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (story.body.isNotBlank() && story.media.isNotEmpty()) {
                Text(
                    text = story.body,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (story.author.id == meId) {
                    androidx.compose.material3.TextButton(
                        onClick = { onShowViewers(story) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Visible),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (story.viewCount == 0) "Belum dilihat" else "${story.viewCount} dilihat",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    androidx.compose.material3.TextButton(
                        onClick = { onReply(story) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Reply),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Balas", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun CreateStoryScreen(
    user: User?,
    onBack: () -> Unit,
    onPost: (String, List<Uri>, String?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var style by rememberSaveable { mutableStateOf(StoryStyles.STYLES.first()) }
    var showPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NusaTopAppBar(
                title = { Text("Story Baru", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = "Tutup")
                    }
                },
                actions = {
                    NusaButton(
                        onClick = { onPost(text, uris, style.takeIf { uris.isEmpty() }) },
                        enabled = text.isNotBlank() || uris.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.padding(end = 8.dp).height(34.dp),
                    ) { Text("Bagikan", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NusaIconButton(onClick = { showPicker = true }) {
                    Icon(painter = painterResource(id = NusaIcons.Photo), contentDescription = "Media", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Hilang dalam 24 jam • ${uris.size}/4",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uris.isEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StoryStyles.STYLES.forEach { option ->
                        StoryStyles.Swatch(
                            style = option,
                            selected = style == option,
                            onClick = { style = option },
                        )
                    }
                }
            }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { if (it.length <= 500) text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                "Tulis story...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        }
                        inner()
                    }
                },
            )
            uris.forEach { uri ->
                val isVideo = remember(uri) {
                    context.contentResolver.getType(uri)?.startsWith("video") == true
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isVideo) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Play),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                    NusaIconButton(
                        onClick = { uris = uris - uri },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = "Hapus", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showPicker) {
        MediaPickerScreen(
            maxSelection = 4 - uris.size,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                showPicker = false
                uris = (uris + picked).take(4)
            },
        )
    }
}
