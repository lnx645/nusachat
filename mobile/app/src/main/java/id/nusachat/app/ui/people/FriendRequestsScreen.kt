package id.nusachat.app.ui.people
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.FriendRequest
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.SectionLabel
import id.nusachat.app.ui.components.UserRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRequestsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val requests by vm.requests.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NusaTopAppBar(
                title = { Text("Teman Baru") },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    NusaIconButton(onClick = { vm.loadRequests() }) {
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
        if (requests.incoming.isEmpty() && requests.outgoing.isEmpty()) {
            EmptyState(
                icon = NusaIcons.PersonAdd,
                title = "Belum ada permintaan",
                subtitle = "Permintaan pertemanan yang masuk dan keluar akan muncul di sini.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (requests.incoming.isNotEmpty()) {
                    item(key = "incoming-label") {
                        SectionLabel("Permintaan masuk (${requests.incoming.size})")
                    }
                    items(requests.incoming, key = { "in-${it.requestId}" }) { request ->
                        RequestRow(
                            request = request,
                            onAccept = { vm.respondToRequest(request, true) },
                            onDecline = { vm.respondToRequest(request, false) },
                        )
                    }
                }
                if (requests.outgoing.isNotEmpty()) {
                    item(key = "outgoing-label") {
                        SectionLabel("Terkirim (${requests.outgoing.size})")
                    }
                    items(requests.outgoing, key = { "out-${it.requestId}" }) { request ->
                        UserRow(
                            user = request.user,
                            subtitle = "Menunggu konfirmasi @${request.user.username}",
                            onClick = {},
                            trailing = {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Time),
                                    contentDescription = "Menunggu",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun RequestRow(
    request: FriendRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    UserRow(
        user = request.user,
        subtitle = "@${request.user.username} ingin berteman",
        onClick = {},
        trailing = {
            NusaIconButton(onClick = onAccept) {
                Icon(
                    painter = painterResource(id = NusaIcons.Check),
                    contentDescription = "Terima",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(23.dp),
                )
            }
            NusaIconButton(onClick = onDecline) {
                Icon(
                    painter = painterResource(id = NusaIcons.Close),
                    contentDescription = "Tolak",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
        },
    )
}
