package id.nusachat.app.ui.moments

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import id.nusachat.app.data.Moment
import id.nusachat.app.data.MomentComment
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaProgress
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.formatMomentTime
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Halaman komentar momen ala WeChat — full screen, post di atas, list komentar,
 * reply threaded, input bawah. Bukan bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentCommentsScreen(
    moment: Moment,
    comments: List<MomentComment>,
    loading: Boolean,
    onBack: () -> Unit,
    onSend: (String, Long?) -> Unit,
    myId: Long = 0L,
    onDelete: (MomentComment) -> Unit = {},
    onToggleLike: (MomentComment) -> Unit = {},
) {
    val isAuthor = moment.author.id == myId
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<MomentComment?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NusaTopAppBar(
            title = { Text("Komentar") },
            navigationIcon = {
                NusaIconButton(onClick = onBack) {
                    Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
        ) {
            item(key = "post-${moment.id}") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NusaAvatar(
                            name = moment.author.displayName,
                            url = moment.author.avatarUrl, gender = moment.author.gender, seed = moment.author.id,
                            size = 40.dp,
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(moment.author.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF576B95))
                            Text(formatMomentTime(moment.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (moment.body.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(moment.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (moment.media.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        moment.media.take(3).forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), thickness = 0.7.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        "${comments.size} komentar",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                loading -> {
                    item(key = "loading") {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            NusaProgress(modifier = Modifier.size(22.dp))
                        }
                    }
                }
                comments.isEmpty() -> {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painter = painterResource(id = NusaIcons.ChatOutline), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Belum ada komentar", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Jadilah yang pertama berkomentar!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    items(comments, key = { it.id }) { c ->
                        CommentRow(
                            comment = c,
                            canDelete = c.user.id == myId || isAuthor,
                            onReply = { replyTo = c },
                            onDelete = { onDelete(c) },
                            onToggleLike = { onToggleLike(c) },
                        )
                    }
                }
            }
        }

        // Reply indicator
        if (replyTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Membalas ${replyTo!!.user.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { replyTo = null }
                        .padding(4.dp),
                ) {
                    Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = "Batal", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = false,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = if (replyTo != null) "Balas @${replyTo!!.user.username}..." else "Tulis komentar...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            NusaIconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onSend(draft, replyTo?.id)
                        draft = ""
                        replyTo = null
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Send),
                    contentDescription = "Kirim",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: MomentComment,
    onReply: () -> Unit,
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
    onToggleLike: () -> Unit = {},
) {
    var confirm by remember { mutableStateOf(false) }
    val isReply = comment.parentId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReply)
            .padding(
                start = if (isReply) 42.dp else 14.dp,
                end = 14.dp,
                top = 7.dp,
                bottom = 7.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        NusaAvatar(
            name = comment.user.displayName,
            url = comment.user.avatarUrl,
            gender = comment.user.gender,
            seed = comment.user.id,
            size = if (isReply) 26.dp else 30.dp,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.user.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatMomentTime(comment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(3.dp))
            // bubble konten — reply punya garis kiri + bg
            val bubbleModifier = if (isReply) {
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 9.dp, vertical = 7.dp)
            } else {
                Modifier
            }
            Box(modifier = bubbleModifier) {
                val annotated = buildAnnotatedString {
                    if (isReply && comment.replyToDisplayName != null) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                            append("@${comment.replyToDisplayName} ")
                        }
                    }
                    append(comment.body)
                }
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Balas",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onReply)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                if (canDelete) {
                    Text(
                        "Hapus",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { confirm = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleLike)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Like),
                        contentDescription = "Suka",
                        tint = if (comment.likedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    if (comment.likeCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${comment.likeCount}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (comment.likedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (confirm) {
        id.nusachat.app.ui.components.NusaConfirmSheet(
            title = "Hapus komentar?",
            text = "Komentar dan balasannya ikut terhapus.",
            confirmLabel = "Hapus",
            danger = true,
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}
