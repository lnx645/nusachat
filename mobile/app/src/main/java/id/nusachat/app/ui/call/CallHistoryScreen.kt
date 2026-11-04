package id.nusachat.app.ui.call

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.CallHistoryItem
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.SkeletonList
import id.nusachat.app.ui.components.formatListTime
import id.nusachat.app.ui.theme.NusaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onCallPeer: (User) -> Unit,
    onVideoPeer: (User) -> Unit = {},
) {
    val history by vm.callHistory.collectAsStateWithLifecycle()
    val loading by vm.callHistoryLoading.collectAsStateWithLifecycle()
    var filterMissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadCallHistory()
        vm.markCallsSeen()
    }

    val shown = if (filterMissed) history.filter { it.missed } else history

    Scaffold(
        topBar = {
            NusaTopAppBar(
                title = { Text("Riwayat panggilan", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    NusaIconButton(onClick = { vm.loadCallHistory() }) {
                        Icon(painter = painterResource(id = NusaIcons.Refresh), contentDescription = "Segarkan")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = !filterMissed,
                    onClick = { filterMissed = false },
                    label = { Text("Semua") },
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilterChip(
                    selected = filterMissed,
                    onClick = { filterMissed = true },
                    label = { Text("Tak terjawab") },
                )
            }
            when {
                loading && history.isEmpty() -> SkeletonList(count = 6)
                shown.isEmpty() -> EmptyState(
                    icon = NusaIcons.History,
                    title = if (filterMissed) "Tidak ada yang terlewat" else "Belum ada panggilan",
                    subtitle = if (filterMissed) "Semua panggilan terjawab. Bagus!" else "Panggilan suara & videomu tercatat di sini.",
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.call.id }) { item ->
                        CallHistoryRow(
                            item = item,
                            onCall = { item.peer?.let(onCallPeer) },
                            onVideo = { item.peer?.let(onVideoPeer) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(item: CallHistoryItem, onCall: () -> Unit, onVideo: () -> Unit) {
    val isRoom = item.call.conversationId != null
    val title = when {
        isRoom -> item.groupTitle ?: "Panggilan grup"
        else -> item.peer?.familiarName ?: "Tak dikenal"
    }
    val subtitle = when {
        item.missed -> "Tak terjawab • ${formatListTime(item.call.createdAt)}"
        item.call.status == "rejected" && item.direction == "outgoing" -> "Ditolak • ${formatListTime(item.call.createdAt)}"
        item.duration > 0 -> "${formatDuration(item.duration)} • ${formatListTime(item.call.createdAt)}"
        else -> formatListTime(item.call.createdAt)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NusaAvatar(
            name = title,
            url = item.peer?.avatarUrl,
            gender = item.peer?.gender,
            seed = item.peer?.id ?: item.call.id,
            size = 46.dp,
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (item.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(
                        id = when {
                            item.missed -> NusaIcons.MissedCall
                            item.direction == "outgoing" -> NusaIcons.CallOut
                            else -> NusaIcons.CallIn
                        }
                    ),
                    contentDescription = null,
                    tint = if (item.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (item.call.kind == "video") {
            Icon(
                painter = painterResource(id = NusaIcons.Video),
                contentDescription = "Video",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (!isRoom && item.peer != null) {
            NusaIconButton(onClick = onVideo, modifier = Modifier.size(38.dp)) {
                Icon(
                    painter = painterResource(id = NusaIcons.Video),
                    contentDescription = "Video call",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            NusaIconButton(onClick = onCall, modifier = Modifier.size(38.dp)) {
                Icon(
                    painter = painterResource(id = NusaIcons.Call),
                    contentDescription = "Telepon balik",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    if (totalSeconds < 60) return "${totalSeconds} dtk"
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m < 60) "%d:%02d".format(m, s) else "%d jam %d mnt".format(m / 60, m % 60)
}
