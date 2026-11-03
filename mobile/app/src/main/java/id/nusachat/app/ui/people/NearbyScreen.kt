package id.nusachat.app.ui.people
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar

import android.Manifest
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.SkeletonList
import id.nusachat.app.ui.components.UserRow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(vm: AppViewModel, onBack: () -> Unit) {
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val radius by vm.nearbyRadius.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(vm.hasLocationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it } || vm.hasLocationPermission()
        if (hasPermission) vm.loadNearby()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.loadNearby()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NusaTopAppBar(
                title = { Text("Orang Terdekat") },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    NusaIconButton(onClick = {
                        if (hasPermission) vm.loadNearby()
                        else permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    }) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasPermission) {
                EmptyState(
                    icon = NusaIcons.Pin,
                    title = "Aktifkan lokasi",
                    subtitle = "NusaChat memakai lokasi perkiraan untuk menampilkan orang di sekitar. Lokasi tidak dibagikan ke publik.",
                )
                NusaButton(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp),
                ) {
                    Text("Izinkan lokasi")
                }
            } else {
                when {
                    nearby.loading -> SkeletonList()
                    nearby.error != null -> {
                        EmptyState(
                            icon = NusaIcons.Pin,
                            title = "Gagal memuat lokasi",
                            subtitle = nearby.error.orEmpty(),
                        )
                        NusaButton(
                            onClick = vm::loadNearby,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 36.dp),
                        ) {
                            Text("Coba lagi")
                        }
                    }
                    nearby.users.isEmpty() -> EmptyState(
                        icon = NusaIcons.Pin,
                        title = "Belum ada orang di sekitar",
                        subtitle = "Coba lagi nanti atau perluas area pencarian.",
                    )
                    else -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "${nearby.users.size} orang ditemukan",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Radius $radius km dari lokasimu",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                painter = painterResource(id = NusaIcons.Pin),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Spacer(Modifier.width(2.dp))
                            listOf(1, 5, 10, 25, 50, 100).forEach { km ->
                                FilterChip(
                                    selected = radius == km,
                                    onClick = { vm.setNearbyRadius(km) },
                                    label = { Text("$km km") },
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(nearby.users, key = { it.id }) { user ->
                                NearbyRow(
                                    user = user,
                                    onChat = { vm.openChatWith(user) },
                                    onAdd = { vm.sendFriendRequest(user) },
                                )
                            }
                            item { Spacer(Modifier.height(12.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(
    user: User,
    onChat: () -> Unit,
    onAdd: () -> Unit,
) {
    val status = user.friendStatus ?: "none"
    val distance = user.distanceKm?.let { km ->
        if (km < 1.0) "${(km * 1000).toInt()} m" else String.format(Locale("id", "ID"), "%.1f km", km)
    } ?: ""

    UserRow(
        user = user,
        subtitle = buildString {
            if (distance.isNotBlank()) append(distance)
            if (distance.isNotBlank() && !user.bio.isNullOrBlank()) append(" • ")
            if (!user.bio.isNullOrBlank()) append(user.bio)
        },
        onClick = if (status == "accepted") onChat else onAdd,
        trailing = {
            when (status) {
                "accepted" -> NusaIconButton(onClick = onChat) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Chat),
                        contentDescription = "Chat",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp),
                    )
                }
                "pending" -> Text(
                    text = "Menunggu",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
                else -> NusaIconButton(onClick = onAdd) {
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
