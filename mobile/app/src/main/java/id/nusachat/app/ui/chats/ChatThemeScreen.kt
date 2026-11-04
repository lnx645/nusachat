package id.nusachat.app.ui.chats

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import id.nusachat.app.R
import id.nusachat.app.data.ChatBubbles
import id.nusachat.app.data.ChatWallpapers
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.theme.ResolvedBubbles
import id.nusachat.app.ui.theme.bubblePair
import id.nusachat.app.ui.theme.builtinWallpapers
import id.nusachat.app.ui.theme.doodleDrawableName
import id.nusachat.app.ui.theme.resolveChatTheme

/**
 * Halaman khusus Latar Chat ala WA/Telegram/LINE: pilih wallpaper
 * (doodle Nusa terang/gelap, warna solid, gambar galeri) + pasangan
 * bubble yang selalu serasi dengan latar.
 */
@Composable
fun ChatThemeScreen(vm: AppViewModel, onBack: () -> Unit) {
    val choice by vm.chatTheme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val resolved = resolveChatTheme(choice.wallpaperId, choice.customUri, choice.bubbleId, systemDark)

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.setChatWallpaper(ChatWallpapers.CUSTOM, uri.toString())
        }
    }

    BackHandler(onBack = onBack)
    // Header sebaris — halaman ini di dalam Scaffold utama, Scaffold + TopAppBar bikin dobel.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NusaIconButton(onClick = onBack) {
                Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
            }
            Text(
                text = "Latar Chat",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            NusaIconButton(onClick = { vm.resetChatTheme() }) {
                Icon(painter = painterResource(id = NusaIcons.Refresh), contentDescription = "Atur ulang")
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    ChatPreview(resolvedBase = resolved.base, wallpaperId = choice.wallpaperId, customUri = choice.customUri, pair = resolved.bubbles)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pratinjau",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("Wallpaper")
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(builtinWallpapers(), key = { it.first }) { (id, label, color) ->
                WallpaperTile(
                    label = label,
                    color = Color(color),
                    doodleRes = if (id == ChatWallpapers.DEFAULT) R.drawable.chat_doodle_light
                    else if (id == ChatWallpapers.DARK) R.drawable.chat_doodle_dark else null,
                    selected = choice.wallpaperId == id,
                    onClick = { vm.setChatWallpaper(id) },
                )
            }
            item {
                CustomTile(
                    uri = if (choice.wallpaperId == ChatWallpapers.CUSTOM) choice.customUri else "",
                    selected = choice.wallpaperId == ChatWallpapers.CUSTOM,
                    onClick = { galleryLauncher.launch(arrayOf("image/*")) },
                )
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("Bubble chat")
                    Text(
                        "Otomatis mengikuti terang/gelap wallpaper.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    BubbleOptionRow(
                        label = "Otomatis",
                        mine = bubblePair(ChatBubbles.AUTO, resolved.isDark).mine,
                        other = bubblePair(ChatBubbles.AUTO, resolved.isDark).other,
                        selected = choice.bubbleId == ChatBubbles.AUTO,
                        onClick = { vm.setChatBubble(ChatBubbles.AUTO) },
                    )
                    listOf(
                        ChatBubbles.NUSA to "Nusa",
                        ChatBubbles.WA to "WA Klasik",
                        ChatBubbles.BLUE to "Biru",
                        ChatBubbles.PURPLE to "Ungu",
                        ChatBubbles.DARK to "Gelap",
                    ).forEach { (id, label) ->
                        val pair = bubblePair(id, resolved.isDark)
                        BubbleOptionRow(
                            label = label,
                            mine = pair.mine,
                            other = pair.other,
                            selected = choice.bubbleId == id,
                            onClick = { vm.setChatBubble(id) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun WallpaperTile(label: String, color: Color, doodleRes: Int?, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .aspectRatio(0.72f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .then(
                    if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (doodleRes != null) {
                Image(
                    painter = painterResource(id = doodleRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Check),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp).size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun CustomTile(uri: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .aspectRatio(0.72f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (uri.isNotBlank()) {
                AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    painter = painterResource(id = NusaIcons.Photo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Galeri", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun BubbleOptionRow(label: String, mine: Color, other: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(other).border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)))
            Box(modifier = Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(mine))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(painter = painterResource(id = NusaIcons.Check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ChatPreview(resolvedBase: Color, wallpaperId: String, customUri: String, pair: ResolvedBubbles) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(resolvedBase),
    ) {
        val doodle = doodleDrawableName(wallpaperId)
        if (doodle != null) {
            val res = if (wallpaperId == ChatWallpapers.DARK) R.drawable.chat_doodle_dark else R.drawable.chat_doodle_light
            Image(painter = painterResource(id = res), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (wallpaperId == ChatWallpapers.CUSTOM && customUri.isNotBlank()) {
            AsyncImage(model = customUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(12.dp), color = pair.other) {
                    Text("Halo!", fontSize = 12.sp, color = pair.otherText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(shape = RoundedCornerShape(12.dp), color = pair.mine) {
                    Text("Hai juga!", fontSize = 12.sp, color = pair.mineText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}
