package id.nusachat.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaSwitch
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.SectionLabel
import id.nusachat.app.ui.theme.NusaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup lokal otomatis (.updb) — tanpa cloud, tanpa server.
 *
 * WhatsApp tanpa cloud-nya: tiap malam HP mengenkripsi chat ke folder tetap
 * `Download/NusaChat/` (AES-256-GCM, kunci milik user). Install ulang di HP
 * yang sama = file masih ada → aplikasi menawari restore otomatis, cukup
 * masukkan kunci. Pindah HP = salin file (USB/kabel/Drive manual) lalu restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val working by vm.backupWorking.collectAsStateWithLifecycle()
    val newKey by vm.backupNewKey.collectAsStateWithLifecycle()
    val backupError by vm.backupError.collectAsStateWithLifecycle()
    val files by vm.localBackups.collectAsStateWithLifecycle()
    val autoEnabled by vm.settings.backupAuto.collectAsStateWithLifecycle()

    var modePass by remember { mutableStateOf(false) }
    var newSecret by remember { mutableStateOf("") }
    var showKeyEditor by remember { mutableStateOf(false) }
    var showStoredKey by remember { mutableStateOf(false) }
    var storedKey by remember { mutableStateOf<String?>(null) }
    var restoreEntryName by remember { mutableStateOf<String?>(null) }
    var restoreRaw by remember { mutableStateOf<String?>(null) }
    var restoreKey by remember { mutableStateOf("") }
    var exportName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.scanLocalBackups() }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val name = exportName
        if (uri == null || name == null) return@rememberLauncherForActivityResult
        exportName = null
        vm.exportLatestCopyTo(uri)
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) {
                withContext(Dispatchers.Main) { vm.restoreLocalBlob("", "") }
            } else {
                withContext(Dispatchers.Main) {
                    restoreRaw = text
                    restoreEntryName = null
                    restoreKey = ""
                }
            }
        }
    }

    Scaffold(
        topBar = {
            NusaTopAppBar(
                title = { Text("Cadangan lokal (.updb)", fontWeight = FontWeight.SemiBold) },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionLabel("Otomatis tiap malam")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Database),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Backup otomatis harian", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Ke Download/NusaChat, saat baterai & storage cukup",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NusaSwitch(checked = autoEnabled, onCheckedChange = { vm.setBackupAuto(it) })
            }
            NusaButton(
                onClick = { vm.backupNow() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(if (working) "Mencadangkan…" else "Cadangkan sekarang")
            }
            Text(
                "Tanpa cloud, tanpa server. File + kunci adalah satu-satunya jalan pulih.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            backupError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            SectionLabel("Kunci backup")
            Text(
                "Kunci dibuat sekali, dipakai tiap backup otomatis. Ganti kunci hanya " +
                    "berlaku untuk backup berikutnya — file lama tetap butuh kunci lama.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                NusaTextButton(onClick = {
                    storedKey = vm.storedBackupKey()
                    showStoredKey = storedKey != null
                    if (storedKey == null) vm.backupNow()
                }) { Text("Lihat kunci") }
                Spacer(Modifier.width(8.dp))
                NusaTextButton(onClick = {
                    newSecret = ""
                    modePass = false
                    showKeyEditor = true
                }) { Text("Ganti kunci") }
            }

            SectionLabel("File di Download/NusaChat • ${files.size}")
            if (files.isEmpty()) {
                Text(
                    "Belum ada file. Nyalakan otomatis atau cadangkan sekarang.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                files.take(10).forEach { entry ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${formatBytes(entry.size)} • ${formatTime(entry.modified)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NusaTextButton(onClick = {
                                restoreEntryName = entry.name
                                restoreRaw = null
                                restoreKey = ""
                            }) { Text("Pulihkan") }
                            NusaTextButton(onClick = {
                                exportName = entry.name
                                createDoc.launch(entry.name)
                            }) { Text("Bagikan/salin") }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }

            SectionLabel("File dari tempat lain")
            Text(
                "Pindah HP: salin file .updb ke HP baru (USB/Drive manual), " +
                    "lalu pilih di sini dan masukkan kunci.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            NusaTextButton(
                onClick = { openDoc.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Pilih file .updb lain…") }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- dialog pulihkan: dari folder tetap atau file SAF luar.
    val restoreActive = restoreEntryName != null || restoreRaw != null
    if (restoreActive) {
        var key by remember(restoreEntryName, restoreRaw) { mutableStateOf(restoreKey) }
        androidx.compose.ui.window.Dialog(onDismissRequest = {
            restoreEntryName = null
            restoreRaw = null
        }) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Pulihkan backup", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (restoreEntryName ?: "File pilihan") +
                            " — masukkan kunci 64 digit / passphrase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Kunci") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        NusaButton(onClick = {
                            val entry = restoreEntryName?.let { n -> files.firstOrNull { it.name == n } }
                            val raw = restoreRaw
                            when {
                                entry != null -> vm.restoreLocalEntry(entry, key) { n ->
                                    if (n > 0) {
                                        restoreEntryName = null
                                        restoreRaw = null
                                    }
                                }
                                raw != null -> vm.restoreLocalBlob(raw, key) { n ->
                                    if (n > 0) {
                                        restoreEntryName = null
                                        restoreRaw = null
                                    }
                                }
                                else -> {
                                    restoreEntryName = null
                                    restoreRaw = null
                                }
                            }
                        }, enabled = !working && key.isNotBlank()) {
                            Text(if (working) "Memulihkan…" else "Pulihkan")
                        }
                        Spacer(Modifier.width(8.dp))
                        NusaTextButton(onClick = {
                            restoreEntryName = null
                            restoreRaw = null
                        }) { Text("Batal") }
                    }
                }
            }
        }
    }

    if (newKey != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Catat kunci ini", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Satu-satunya kesempatan. Tanpa kunci ini file .updb tidak bisa dibuka.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(newKey!!.chunked(8).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    NusaButton(onClick = {
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("updb-key", newKey!!))
                        }
                        vm.clearBackupNewKey()
                    }) { Text("Salin & tutup") }
                }
            }
        }
    }

    if (showStoredKey && storedKey != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showStoredKey = false }) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Kunci backup aktif", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(storedKey!!.chunked(8).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    NusaButton(onClick = {
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("updb-key", storedKey!!))
                        }
                        showStoredKey = false
                    }) { Text("Salin & tutup") }
                }
            }
        }
    }

    if (showKeyEditor) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showKeyEditor = false }) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Ganti kunci backup", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (modePass) "Passphrase" else "64 digit acak", modifier = Modifier.weight(1f))
                        NusaSwitch(checked = modePass, onCheckedChange = {
                            modePass = it
                            newSecret = ""
                        })
                    }
                    if (modePass) {
                        OutlinedTextField(
                            value = newSecret,
                            onValueChange = { newSecret = it },
                            label = { Text("Passphrase baru (min. 4)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        NusaTextButton(onClick = {
                            newSecret = id.nusachat.app.data.ChatBackup.generateDigits()
                        }) { Text(if (newSecret.isBlank()) "Buat kunci acak" else "Buat ulang") }
                        if (newSecret.isNotBlank()) {
                            SelectionContainer {
                                Text(newSecret.chunked(8).joinToString("\n"),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        NusaButton(onClick = {
                            val final = if (modePass) newSecret.trim()
                            else newSecret.trim().ifBlank { id.nusachat.app.data.ChatBackup.generateDigits() }
                            showKeyEditor = false
                            vm.setBackupKey(if (modePass) "pass" else "digits", final)
                        }, enabled = if (modePass) newSecret.trim().length >= 4
                        else newSecret.isBlank() || id.nusachat.app.data.ChatBackup.isValidDigits(newSecret.trim())) {
                            Text("Simpan kunci")
                        }
                        Spacer(Modifier.width(8.dp))
                        NusaTextButton(onClick = { showKeyEditor = false }) { Text("Batal") }
                    }
                }
            }
        }
    }
}

private fun formatBytes(n: Long): String {
    if (n <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return (if (v >= 100) "%.0f" else "%.1f").format(v) + " " + units[i]
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "-"
    val f = java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale("id"))
    return f.format(java.util.Date(ms))
}

/**
 * Prompt restore otomatis di install baru: file .updb valid ditemukan di
 * Download/NusaChat saat cache kosong. Cukup masukkan kunci — tanpa cari file.
 */
@Composable
fun BackupRestorePrompt(
    entryName: String,
    entryDetail: String,
    working: Boolean,
    onRestore: (key: String) -> Unit,
    onSkip: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Database),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Backup ditemukan", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "$entryName ($entryDetail). Masukkan kunci backup untuk memulihkan chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Kunci 64 digit / passphrase") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    NusaButton(
                        onClick = { onRestore(key) },
                        enabled = !working && key.isNotBlank(),
                    ) {
                        Text(if (working) "Memulihkan…" else "Pulihkan")
                    }
                    Spacer(Modifier.width(8.dp))
                    NusaTextButton(onClick = onSkip) { Text("Lewati") }
                }
            }
        }
    }
}
