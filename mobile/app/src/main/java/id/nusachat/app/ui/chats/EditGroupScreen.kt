package id.nusachat.app.ui.chats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import id.nusachat.app.data.Conversation
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaLineInput
import id.nusachat.app.ui.components.NusaProgress
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.theme.NusaIcons
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditGroupScreen(
    conversation: Conversation,
    vm: id.nusachat.app.ui.AppViewModel,
    onBack: () -> Unit,
) {
    var title by remember(conversation.id) { mutableStateOf(conversation.title) }
    var description by remember(conversation.id) { mutableStateOf(conversation.description.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var avatarUrl by remember(conversation.id) { mutableStateOf(conversation.avatarUrl) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            try {
                val url = vm.uploadGroupAvatar(uri)
                avatarUrl = url
            } catch (e: Exception) {
                error = e.message ?: "Gagal upload foto"
            } finally {
                uploading = false
            }
        }
    }

    fun save() {
        val t = title.trim()
        if (t.length < 2) { error = "Nama grup minimal 2 karakter."; return }
        if (t.length > 60) { error = "Nama grup maksimal 60 karakter."; return }
        val d = description.trim()
        if (d.length > 300) { error = "Deskripsi maksimal 300 karakter."; return }
        error = null
        scope.launch {
            try {
                val newTitle = if (t != conversation.title) t else null
                val newAvatar = if (avatarUrl != conversation.avatarUrl) avatarUrl else null
                val newDesc = if (d != conversation.description.orEmpty()) d else null
                if (newTitle == null && newAvatar == null && newDesc == null) { onBack(); return@launch }
                vm.updateGroupInfo(conversation.id, newTitle, newAvatar, newDesc)
                onBack()
            } catch (e: Exception) {
                error = e.message ?: "Gagal menyimpan"
            }
        }
    }

    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NusaTopAppBar(
                title = { Text("Edit grup") },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = { save() }) { Text("Simpan") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !uploading) { picker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        NusaAvatar(name = title.ifBlank { conversation.displayTitle }, url = avatarUrl, gender = null, seed = conversation.id, size = 100.dp)
                        if (uploading) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                NusaProgress(modifier = Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !uploading) { picker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painter = painterResource(id = NusaIcons.Camera), contentDescription = "Ganti foto", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
                androidx.compose.material3.TextButton(onClick = { picker.launch("image/*") }) { Text("Ganti foto grup") }
            }

            Box(modifier = Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant))

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                NusaLineInput(
                    label = "Nama grup",
                    value = title,
                    onValueChange = { if (it.length <= 60) title = it },
                    counter = "${title.length}/60",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                NusaLineInput(
                    label = "Deskripsi grup",
                    value = description,
                    onValueChange = { if (it.length <= 300) description = it },
                    counter = "${description.length}/300",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(text = error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "${conversation.memberCount} anggota • Dibuat oleh ${conversation.createdBy}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                NusaButton(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Simpan perubahan") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
