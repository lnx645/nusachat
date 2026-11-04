package id.nusachat.app.ui.people
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar

import android.Manifest
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.SkeletonList
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
        if (hasPermission) vm.startNearbyLive()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.startNearbyLive()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.stopNearbyLive() }
    }

    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NusaTopAppBar(
                title = {
                    Column {
                        Text("Orang Terdekat")
                        if (hasPermission && nearby.users.isNotEmpty()) {
                            Text(
                                "${nearby.users.size} orang • ${radius}km",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    NusaIconButton(onClick = {
                        if (hasPermission) vm.startNearbyLive()
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
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Pin),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Aktifkan lokasi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Temukan teman di sekitarmu. Lokasi hanya dipakai untuk pencarian dan tidak dibagikan sebagai postingan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    NusaButton(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painter = painterResource(id = NusaIcons.Pin), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Izinkan lokasi")
                    }
                }
            } else {
                when {
                    nearby.loading -> SkeletonList()
                    nearby.error != null -> {
                        EmptyState(
                            icon = NusaIcons.Pin,
                            title = "Gagal memuat",
                            subtitle = nearby.error.orEmpty(),
                        )
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
                            NusaButton(onClick = vm::loadNearby) { Text("Coba lagi") }
                        }
                    }
                    nearby.users.isEmpty() -> {
                        EmptyState(
                            icon = NusaIcons.Pin,
                            title = "Belum ada orang di sekitar",
                            subtitle = "Coba perluas radius atau kembali lagi nanti.",
                        )
                    }
                    else -> {
                        // Header count — flat, no card
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Pin),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(
                                    "${nearby.users.size} orang dalam ${radius}km",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (nearby.live) "Live • realtime" else "Urut terdekat dulu",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (nearby.live) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Radius chips — pill custom, no FilterChip default
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(1, 5, 10, 25, 50, 100).forEach { km ->
                                val selected = radius == km
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { vm.setNearbyRadius(km) },
                                ) {
                                    Text(
                                        "$km km",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            thickness = 0.7.dp,
                        )
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(nearby.users, key = { it.id }) { user ->
                                NearbyRow(
                                    user = user,
                                    onChat = { vm.openChatWith(user) },
                                    onAdd = { vm.sendFriendRequest(user) },
                                )
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(start = 68.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    thickness = 0.6.dp,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (status == "accepted") onChat else onAdd)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NusaAvatar(name = user.displayName, url = user.avatarUrl, gender = user.gender, seed = user.id, size = 46.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (distance.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            distance,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    user.bio?.takeIf { it.isNotBlank() } ?: "@${user.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        when (status) {
            "accepted" -> NusaIconButton(onClick = onChat) {
                Icon(
                    painter = painterResource(id = NusaIcons.Chat),
                    contentDescription = "Chat",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }
            "pending" -> Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "Menunggu",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            else -> NusaButton(
                onClick = onAdd,
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(50),
            ) {
                Icon(painter = painterResource(id = NusaIcons.PersonAdd), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Tambah", style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
            }
        }
    }
}
