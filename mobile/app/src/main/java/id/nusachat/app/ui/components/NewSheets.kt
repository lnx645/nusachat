package id.nusachat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.data.RedPacket
import id.nusachat.app.data.StoryViewer
import id.nusachat.app.data.User
import id.nusachat.app.ui.theme.NusaIcons

/** Kumpulan stiker emoji bawaan NusaChat (tanpa unduhan). */
val NUSA_STICKERS = listOf(
    "🦁", "🐼", "🐸", "🐙", "🦊", "🐯", "🐨", "🐷",
    "🐵", "🐔", "🐢", "🦄", "🐝", "🐳", "🌚", "🔥",
    "💯", "🎉", "❤️", "👍", "😭", "🤣", "😍", "🙏",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Stiker Nusa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.heightIn(max = 300.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(NUSA_STICKERS) { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 34.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clickable { onPick(emoji) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

val REPORT_REASONS = listOf(
    "spam" to "Spam",
    "abuse" to "Pelecehan",
    "fraud" to "Penipuan",
    "porn" to "Konten pornografi",
    "violence" to "Kekerasan",
    "other" to "Lainnya",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onSubmit: (reason: String, details: String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("spam") }
    var details by remember { mutableStateOf("") }
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Laporkan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Laporanmu anonim dan ditinjau tim NusaChat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            REPORT_REASONS.forEach { (code, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { reason = code }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = reason == code, onClick = { reason = code })
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            NusaTextField(
                value = details,
                onValueChange = { if (it.length <= 500) details = it },
                placeholder = { Text("Keterangan tambahan (opsional)") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NusaButton(
                onClick = { onSubmit(reason, details.trim()) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Kirim laporan", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSheet(
    title: String,
    subtitle: String,
    requireOld: Boolean = false,
    confirmLabel: String = "Simpan",
    onSubmit: (pin: String, oldPin: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var oldPin by remember { mutableStateOf("") }
    val valid = pin.matches(Regex("^[0-9]{4,6}$")) && (!requireOld || oldPin.isNotEmpty())
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            if (requireOld) {
                NusaTextField(
                    value = oldPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) oldPin = it },
                    placeholder = { Text("PIN lama") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
            NusaTextField(
                value = pin,
                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                placeholder = { Text(if (requireOld) "PIN baru (4-6 digit)" else "PIN (4-6 digit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NusaButton(
                onClick = { onSubmit(pin, oldPin) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikersSheet(title: String, users: List<User>, onDismiss: () -> Unit) {
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (users.isEmpty()) {
                Text(
                    "Belum ada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp).padding(bottom = 20.dp)) {
                    items(users, key = { it.id }) { user ->
                        UserRow(
                            user = user,
                            subtitle = "@${user.username}",
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewersSheet(count: Int, viewers: List<StoryViewer>, onDismiss: () -> Unit) {
    LikersSheet(
        title = if (count == 0) "Belum dilihat" else "Dilihat $count orang",
        users = viewers.map { it.user },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedPacketSheet(
    packet: RedPacket,
    myId: Long,
    loading: Boolean,
    onClaim: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isSender = packet.sender.id == myId
    val canClaim = !isSender && packet.myClaim == null && packet.status == "active"
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                Icon(
                    painter = painterResource(id = NusaIcons.Gift),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(14.dp).size(30.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = packet.note?.takeIf { it.isNotBlank() } ?: "Amplop merah untukmu",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "dari ${packet.sender.familiarName} • ${packet.claimedCount}/${packet.totalCount} diambil",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (packet.myClaim != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Kamu dapat ${packet.myClaim} koin!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (packet.status != "active") {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (packet.status == "expired") "Amplop kedaluwarsa." else "Amplop sudah habis.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            if (canClaim) {
                NusaButton(
                    onClick = onClaim,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(if (loading) "Membuka..." else "Buka amplop", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (packet.claims.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp).padding(bottom = 20.dp)) {
                    items(packet.claims, key = { it.user.id }) { claim ->
                        UserRow(
                            user = claim.user,
                            subtitle = "${claim.amount} koin",
                            onClick = {},
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedPacketCreateSheet(
    balance: Long,
    memberCount: Int,
    hasPin: Boolean,
    onSubmit: (total: Long, count: Int, note: String, pin: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var total by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val totalNum = total.toLongOrNull() ?: 0L
    val countNum = count.toIntOrNull() ?: 0
    val maxCount = memberCount.coerceIn(1, 50)
    val error = when {
        totalNum < 1 -> "Isi total koin minimal 1."
        totalNum > balance -> "Saldo tidak cukup (Rp $balance)."
        countNum < 1 || countNum > maxCount -> "Jumlah amplop 1-$maxCount untuk chat ini."
        totalNum < countNum -> "Minimal 1 koin per amplop."
        hasPin && pin.isBlank() -> "Isi PIN dompetmu."
        else -> null
    }
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Amplop merah", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Bagi koin secara acak (luck draw). Saldo: $balance koin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            NusaTextField(
                value = total,
                onValueChange = { if (it.length <= 9 && it.all(Char::isDigit)) total = it },
                placeholder = { Text("Total koin") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            NusaTextField(
                value = count,
                onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) count = it },
                placeholder = { Text("Jumlah amplop (maks $maxCount)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            NusaTextField(
                value = note,
                onValueChange = { if (it.length <= 200) note = it },
                placeholder = { Text("Ucapan (opsional)") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasPin) {
                Spacer(Modifier.height(10.dp))
                NusaTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    placeholder = { Text("PIN dompet") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(14.dp))
            NusaButton(
                onClick = { onSubmit(totalNum, countNum, note.trim(), pin) },
                enabled = error == null,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Kirim amplop", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileLinkSheet(username: String, displayName: String, onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val link = "nusachat://user/$username"
    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tambah $displayName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            val qr = remember(link) { id.nusachat.app.data.QrHelper.encode(link, 480) }
            if (qr != null) {
                androidx.compose.foundation.Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Kode QR profil",
                    modifier = Modifier
                        .size(190.dp)
                        .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = link,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Bagikan tautan ini — teman bisa menempelkannya di Cari teman untuk menambahkanmu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NusaOutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f).height(44.dp)) {
                    Text("Salin")
                }
                NusaButton(onClick = onShare, modifier = Modifier.weight(1f).height(44.dp)) {
                    Text("Bagikan", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
