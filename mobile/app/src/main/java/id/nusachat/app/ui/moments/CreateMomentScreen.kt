package id.nusachat.app.ui.moments
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.NusaTextField

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import id.nusachat.app.data.User
import id.nusachat.app.ui.components.MediaPickerScreen
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.theme.NusaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMomentScreen(
    user: User?,
    onBack: () -> Unit,
    onPost: (String, List<Uri>) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NusaTopAppBar(
                title = { Text("Buat Momen", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = "Tutup")
                    }
                },
                actions = {
                    NusaButton(
                        onClick = { onPost(text, imageUris) },
                        enabled = text.isNotBlank() || imageUris.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.padding(end = 8.dp).height(34.dp),
                    ) { Text("Posting", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NusaIconButton(onClick = {
                    val has = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else {
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (has) showPicker = true else showPicker = true // picker will show empty + request
                }) {
                    Icon(painter = painterResource(id = NusaIcons.Photo), contentDescription = "Foto", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${text.length}/2000 • ${imageUris.size}/9",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface),
        ) {
            // Header user kecil ala WeChat — tanpa kotak ekstra
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NusaAvatar(name = user?.displayName ?: "NusaChat", url = user?.avatarUrl, gender = user?.gender, seed = user?.id, size = 36.dp)
                Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(user?.displayName ?: "NusaChat", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text("@${user?.username ?: "nusa"} • Teman saja", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), thickness = 0.7.dp)
            // Text area ala WeChat: tanpa border, hint besar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= 2000) text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp),
                    modifier = Modifier.fillMaxSize(),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize()) {
                            if (text.isEmpty()) {
                                Text(
                                    "Bagikan momenmu...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (imageUris.isNotEmpty() || true) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // Grid foto ala WeChat — tambah kotak + di akhir
                    val allItems: List<Uri?> = imageUris.map { it as Uri? } + listOf(null)
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        modifier = Modifier.height(if (imageUris.size <= 3) 110.dp else if (imageUris.size <= 6) 220.dp else 330.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        userScrollEnabled = false,
                    ) {
                        items(allItems.size.coerceAtMost(9)) { idx ->
                            val uri = allItems[idx]
                            if (uri == null) {
                                if (imageUris.size >= 9) return@items
                                Box(
                                    modifier = Modifier
                                        .size(106.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { showPicker = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painter = painterResource(id = NusaIcons.Add), contentDescription = "Tambah foto", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                                }
                            } else {
                                val isVideo = remember(uri) {
                                    context.contentResolver.getType(uri)?.startsWith("video") == true
                                }
                                Box(modifier = Modifier.size(106.dp).clip(RoundedCornerShape(8.dp))) {
                                    if (isVideo) {
                                        Box(modifier = Modifier.matchParentSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                            Icon(painter = painterResource(id = NusaIcons.Play), contentDescription = "Video", tint = Color.White, modifier = Modifier.size(26.dp))
                                        }
                                    } else {
                                        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                    }
                                    Box(
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { imageUris = imageUris - uri },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (imageUris.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("${imageUris.size}/9", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showPicker) {
        MediaPickerScreen(
            maxSelection = 9 - imageUris.size,
            onDismiss = { showPicker = false },
            onConfirm = { uris ->
                showPicker = false
                imageUris = (imageUris + uris).take(9)
            },
        )
    }
}
