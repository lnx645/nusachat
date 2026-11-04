package id.nusachat.app.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.chats.components.messagePreviewText
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.SkeletonList
import id.nusachat.app.ui.components.formatListTime
import id.nusachat.app.ui.theme.NusaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(vm: AppViewModel, onBack: () -> Unit, onOpenChat: (Long) -> Unit) {
    val messages by vm.starredMessages.collectAsStateWithLifecycle()
    val loading by vm.starredLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadStarred() }

    Scaffold(
        topBar = {
            NusaTopAppBar(
                title = { Text("Pesan berbintang", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        when {
            loading && messages.isEmpty() -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                SkeletonList(count = 6)
            }
            messages.isEmpty() -> EmptyState(
                icon = NusaIcons.Star,
                title = "Belum ada bintang",
                subtitle = "Tahan pesan lalu pilih Bintangi untuk menyimpannya di sini.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(messages, key = { it.id }) { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(msg.conversationId) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = msg.conversationTitle ?: "Obrolan",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                            Text(
                                text = "${msg.sender?.familiarName ?: ""}: ${messagePreviewText(msg, 90)}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatListTime(msg.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NusaIconButton(onClick = { vm.unstarMessage(msg) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                painter = painterResource(id = NusaIcons.Close),
                                contentDescription = "Hapus bintang",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}
