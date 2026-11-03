package id.nusachat.app.ui.components

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import id.nusachat.app.ui.theme.NusaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaItem(val uri: Uri, val isVideo: Boolean, val date: Long)

suspend fun queryMedia(context: Context, limit: Int = 300): List<MediaItem> = withContext(Dispatchers.IO) {
    val out = mutableListOf<MediaItem>()
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)"
    val args = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )
    val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
    runCatching {
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection, selection, args, sort
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(idCol)
                val type = c.getInt(typeCol)
                val date = c.getLong(dateCol)
                val uri = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
                out.add(MediaItem(uri, type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, date))
            }
        }
    }
    out
}

/**
 * Half-sheet ala LINE: handle 36x4, header Recent, grid 3 kolom 8dp, camera tile, seleksi hijau.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPickerScreen(
    maxSelection: Int = 9,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        items = queryMedia(context)
        loading = false
    }

    NusaBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pilih foto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${selected.size}/$maxSelection", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            if (loading) {
                SkeletonList(count = 4)
            } else if (items.isEmpty()) {
                EmptyState(icon = NusaIcons.Photo, title = "Tidak ada foto", subtitle = "Izinkan akses media atau simpan foto terlebih dahulu.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        val isSelected = selected.contains(item.uri)
                        val index = selected.indexOf(item.uri) + 1
                        Box(
                            modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                selected = if (isSelected) selected - item.uri else if (selected.size >= maxSelection) selected else selected + item.uri
                            }
                        ) {
                            AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            if (item.isVideo) {
                                Box(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                    Text("VIDEO", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                            Box(
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f))
                                    .border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) Text("$index", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NusaTextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Batal") }
                NusaButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Pilih (${selected.size})") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun MediaPickerSheet(
    maxSelection: Int = 9,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
) = MediaPickerScreen(maxSelection = maxSelection, onDismiss = onDismiss, onConfirm = onConfirm)
