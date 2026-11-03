package id.nusachat.app.ui
import id.nusachat.app.ui.components.NusaBottomSheet

import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import id.nusachat.app.ui.theme.NusaIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.BuildConfig
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaOnlineGreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    vm: AppViewModel,
    onOpenMoments: () -> Unit,
    onOpenNearby: () -> Unit,
) {
    var showAbout by remember { mutableStateOf(false) }
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val user = session.user

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(
                    text = "Jelajah",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Temukan hal baru di sekitarmu",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                Color(0xFF0B8F6A),
                                Color(0xFF28B58A),
                            )
                        )
                    )
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NusaAvatar(
                        name = user?.displayName ?: "NusaChat",
                        url = user?.avatarUrl,
                        size = 52.dp,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text(
                            text = "Halo, ${user?.displayName ?: "teman"}!",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Bagikan momen atau temukan orang terdekat.",
                            color = Color.White.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(id = NusaIcons.Explore),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        item(key = "menu") {
            SurfaceGroup {
                DiscoverRow(
                    icon = NusaIcons.Photo,
                    tint = Color(0xFFE39B1D),
                    background = Color(0xFFFFF2D9),
                    title = "Momen",
                    subtitle = "Lihat kabar terbaru dari teman",
                    onClick = onOpenMoments,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DiscoverRow(
                    icon = NusaIcons.Pin,
                    tint = MaterialTheme.colorScheme.primary,
                    background = MaterialTheme.colorScheme.primaryContainer,
                    title = "Orang Terdekat",
                    subtitle = "Temukan pengguna di sekitar lokasimu",
                    onClick = onOpenNearby,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DiscoverRow(
                    icon = NusaIcons.Info,
                    tint = Color(0xFF4A78D0),
                    background = Color(0xFFE6EEFF),
                    title = "Tentang NusaChat",
                    subtitle = "Versi aplikasi dan info server",
                    onClick = { showAbout = true },
                )
            }
        }
    }

    if (showAbout) {
        NusaBottomSheet(
            onDismissRequest = { showAbout = false },
            containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Tentang NusaChat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text("NusaChat 0.1.0", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Klien Android untuk API NusaChat. Dibangun dengan Jetpack Compose dan backend CodeIgniter.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "API: ${BuildConfig.API_BASE_URL}\nWebSocket: ${BuildConfig.WS_URL}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SurfaceGroup(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column { content() }
    }
}

@Composable
private fun DiscoverRow(
    icon: Int,
    tint: Color,
    background: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(23.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(id = NusaIcons.Next),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}
