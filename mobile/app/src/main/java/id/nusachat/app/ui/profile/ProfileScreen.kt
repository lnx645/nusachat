package id.nusachat.app.ui.profile
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaProgress
import id.nusachat.app.ui.components.NusaSwitch
import id.nusachat.app.ui.components.NusaTextField

import android.net.Uri
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.BuildConfig
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.NusaOnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(
    vm: AppViewModel,
    user: User,
    onEditProfile: () -> Unit,
) {
    val connected by vm.realtimeConnected.collectAsStateWithLifecycle()
    var showLogout by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.background,
                        )
                    )
                ),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    NusaAvatar(name = user.displayName, url = user.avatarUrl, size = 70.dp)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                ) {
                    Text(
                        text = user.displayName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "@${user.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (connected) NusaOnlineGreen
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(
                            text = if (connected) "Online" else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                NusaIconButton(onClick = onEditProfile) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Edit),
                        contentDescription = "Edit profil",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column {
                ProfileRow(
                    icon = NusaIcons.Edit,
                    title = "Edit Profil",
                    subtitle = "Nama tampilan, bio, dan foto profil",
                    onClick = onEditProfile,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 64.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ProfileRow(
                    icon = NusaIcons.Settings,
                    title = "Pengaturan",
                    subtitle = "Info server dan koneksi",
                    onClick = { showSettings = true },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 64.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ProfileRow(
                    icon = NusaIcons.Info,
                    title = "Tentang NusaChat",
                    subtitle = "Versi 0.1.0 • Jetpack Compose",
                    onClick = { showAbout = true },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column {
                ProfileRow(
                    icon = NusaIcons.Lock,
                    title = "Ganti Kata Sandi",
                    subtitle = "Amankan akunmu secara berkala",
                    onClick = { showPassword = true },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 64.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ProfileRow(
                    icon = NusaIcons.Logout,
                    title = "Keluar",
                    subtitle = "Hapus sesi dari perangkat ini",
                    tint = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    onClick = { showLogout = true },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "NusaChat 0.1.0 • dibuat untuk belajar",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    if (showLogout) {
        NusaConfirmSheet(
            title = "Keluar dari NusaChat?",
            text = "Kamu perlu masuk kembali untuk membuka obrolan dan momen.",
            confirmLabel = "Keluar",
            danger = true,
            onConfirm = {
                showLogout = false
                vm.logout()
            },
            onDismiss = { showLogout = false },
        )
    }

    if (showSettings) {
        NusaBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Pengaturan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                SettingsLine("Status realtime", if (connected) "Terhubung" else "Terputus")
                SettingsLine("API", BuildConfig.API_BASE_URL)
                SettingsLine("WebSocket", BuildConfig.WS_URL)
                SettingsLine("Penyimpanan sesi", "SharedPreferences privat")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Untuk mengubah alamat server, edit buildConfigField di app/build.gradle.kts lalu build ulang.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
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
                Text("NusaChat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Klien chat ringan dengan obrolan langsung, grup, momen, dan orang terdekat.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Dibangun dengan Jetpack Compose, OkHttp, dan backend CodeIgniter 3. Tanpa fitur monetisasi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showPassword) {
        ChangePasswordSheet(
            onDismiss = { showPassword = false },
            onConfirm = { old, new -> showPassword = false; vm.changePassword(old, new) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangePasswordSheet(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var old by rememberSaveable { mutableStateOf("") }
    var newPass by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    NusaBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Ganti Kata Sandi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            NusaTextField(value = old, onValueChange = { old = it }, label = { Text("Sandi lama") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            NusaTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("Sandi baru (min 6)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            NusaTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Ulangi sandi baru") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Setelah diganti kamu akan diminta masuk ulang.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            NusaButton(
                onClick = {
                    if (newPass.length < 6) { error = "Sandi baru minimal 6 karakter."; return@NusaButton }
                    if (newPass != confirm) { error = "Konfirmasi tidak cocok."; return@NusaButton }
                    onConfirm(old, newPass)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Ganti kata sandi", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProfileRow(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary,
    showChevron: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(23.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = tint)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showChevron) {
            Icon(
                painter = painterResource(id = NusaIcons.Next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    vm: AppViewModel,
    user: User,
    onBack: () -> Unit,
) {
    var displayName by rememberSaveable { mutableStateOf(user.displayName) }
    var bio by rememberSaveable { mutableStateOf(user.bio.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(user.phone.orEmpty()) }
    var gender by rememberSaveable { mutableStateOf(user.gender.orEmpty()) }
    var birthday by rememberSaveable { mutableStateOf(user.birthday.orEmpty()) }
    var discoverable by rememberSaveable { mutableStateOf(user.discoverable) }
    var localError by remember { mutableStateOf<String?>(null) }
    val uploading by vm.uploadingAvatar.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(vm::uploadAvatar)
    }

    fun save() {
        if (displayName.trim().length < 2) {
            localError = "Nama tampilan minimal 2 karakter."
            return
        }
        if (displayName.trim().length > 40) {
            localError = "Nama tampilan maksimal 40 karakter."
            return
        }
        if (bio.trim().length > 160) {
            localError = "Bio maksimal 160 karakter."
            return
        }
        if (birthday.isNotBlank() && !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(birthday.trim())) {
            localError = "Tanggal lahir format YYYY-MM-DD."
            return
        }
        localError = null
        vm.updateProfileExtended(
            displayName, bio, phone,
            gender,
            birthday.trim(),
            discoverable,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NusaTopAppBar(
                title = { Text("Edit Profil") },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    NusaTextButton(onClick = { save() }) { Text("Simpan") }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(enabled = !uploading) { picker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    NusaAvatar(name = user.displayName, url = user.avatarUrl, size = 96.dp)
                    if (uploading) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            NusaProgress(
                                modifier = Modifier.size(26.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Camera),
                        contentDescription = "Ganti foto",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Ketuk foto untuk menggantinya",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            NusaTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Nama tampilan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            NusaTextField(
                value = "@${user.username}",
                onValueChange = {},
                label = { Text("Username") },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            NusaTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Nomor HP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            NusaTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("Bio") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Gender",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("" to "Tidak diisi", "male" to "Laki-laki", "female" to "Perempuan", "other" to "Lainnya").forEach { (value, label) ->
                    FilterChip(
                        selected = gender == value,
                        onClick = { gender = value },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            NusaTextField(
                value = birthday,
                onValueChange = { birthday = it },
                label = { Text("Tanggal lahir (YYYY-MM-DD)") },
                placeholder = { Text("1999-01-31") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tampil di Orang Terdekat", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Matikan agar lokasimu tidak ditemukan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NusaSwitch(checked = discoverable, onCheckedChange = { discoverable = it })
            }
            if (localError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = localError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
            NusaButton(
                onClick = { save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Simpan perubahan", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
