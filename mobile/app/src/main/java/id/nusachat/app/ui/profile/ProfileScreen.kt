package id.nusachat.app.ui.profile
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaDatePickerDialog
import id.nusachat.app.ui.components.NusaLineInput
import id.nusachat.app.ui.components.NusaLinePickerInput
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.chats.ChatThemeScreen
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaProgress
import id.nusachat.app.ui.components.NusaSwitch

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onOpenSettings: () -> Unit = {},
) {
    val connected by vm.realtimeConnected.collectAsStateWithLifecycle()
    var showLogout by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    if (showPassword) {
        ChangePasswordScreen(
            onBack = { showPassword = false },
            onConfirm = { old, new ->
                showPassword = false
                vm.changePassword(old, new)
            },
        )
        return
    }

    if (showDelete) {
        DeleteAccountScreen(
            onBack = { showDelete = false },
            onConfirm = { password -> vm.deleteAccount(password) },
        )
        return
    }

    var showWallet by remember { mutableStateOf(false) }
    if (showWallet) {
        WalletScreen(vm = vm, onBack = { showWallet = false })
        return
    }

    var showChatTheme by remember { mutableStateOf(false) }
    if (showChatTheme) {
        ChatThemeScreen(vm = vm, onBack = { showChatTheme = false })
        return
    }

    // Halaman "Saya" ala LINE Home: flat putih, tanpa kartu melayang / gradient.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header profil LINE: avatar kiri, nama, status message, chevron.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onEditProfile)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                NusaAvatar(name = user.displayName, url = user.avatarUrl, gender = user.gender, seed = user.id, size = 62.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(
                            if (connected) NusaOnlineGreen
                            else MaterialTheme.colorScheme.outline
                        ),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = user.bio?.takeIf { it.isNotBlank() }
                        ?: "@${user.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = if (connected) "Online" else "Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected) NusaOnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(id = NusaIcons.Next),
                contentDescription = "Edit profil",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
        }

        SectionLabel("Akun")
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ProfileRow(
                icon = NusaIcons.Edit,
                title = "Edit Profil",
                subtitle = "Nama tampilan, bio, dan foto profil",
                onClick = onEditProfile,
            )
            ProfileDivider()
            ProfileRow(
                icon = NusaIcons.Lock,
                title = "Ganti Kata Sandi",
                subtitle = "Amankan akunmu secara berkala",
                onClick = { showPassword = true },
            )
            ProfileDivider()
            ProfileRow(
                icon = NusaIcons.Wallet,
                title = "Dompet Saya",
                subtitle = "Koin, top up, dan kirim ke teman",
                onClick = { showWallet = true },
            )
        }

        SectionLabel("Umum")
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ProfileRow(
                icon = NusaIcons.Photo,
                title = "Latar Chat",
                subtitle = "Wallpaper doodle, warna, dan bubble",
                onClick = { showChatTheme = true },
            )
            ProfileDivider()
            ProfileRow(
                icon = NusaIcons.Settings,
                title = "Pengaturan",
                subtitle = "Tema, notifikasi, perangkat, penyimpanan",
                onClick = onOpenSettings,
            )
            ProfileDivider()
            ProfileRow(
                icon = NusaIcons.Info,
                title = "Tentang NusaChat",
                subtitle = "Versi 0.2.0",
                onClick = { showAbout = true },
            )
        }

        Spacer(Modifier.height(18.dp))
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ProfileRow(
                icon = NusaIcons.Logout,
                title = "Keluar",
                subtitle = "Hapus sesi dari perangkat ini",
                tint = MaterialTheme.colorScheme.error,
                showChevron = false,
                onClick = { showLogout = true },
            )
            ProfileDivider()
            ProfileRow(
                icon = NusaIcons.Delete,
                title = "Hapus akun",
                subtitle = "Nonaktifkan dan anonimkan akun permanen",
                tint = MaterialTheme.colorScheme.error,
                showChevron = false,
                onClick = { showDelete = true },
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "NusaChat 0.2.0",
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

}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 7.dp),
    )
}

@Composable
private fun ProfileDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.7.dp,
    )
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
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface,
            )
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
    var username by rememberSaveable(user.username) { mutableStateOf(user.username) }
    var bio by rememberSaveable { mutableStateOf(user.bio.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(user.phone.orEmpty()) }
    var gender by rememberSaveable { mutableStateOf(user.gender.orEmpty()) }
    var birthday by rememberSaveable { mutableStateOf(user.birthday.orEmpty()) }
    var discoverable by rememberSaveable { mutableStateOf(user.discoverable) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val uploading by vm.uploadingAvatar.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(vm::uploadAvatarCropped)
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
        val uname = username.trim().lowercase()
        if (!Regex("^[a-z0-9_.]{4,20}$").matches(uname)) {
            localError = "Username 4-20 karakter: huruf, angka, titik, garis bawah."
            return
        }
        localError = null
        if (uname != user.username) {
            vm.updateUsername(uname)
        }
        vm.updateProfileExtended(
            displayName, bio, phone,
            gender,
            birthday.trim(),
            discoverable,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
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
            // Foto profil: tengah, badge kamera, ala LINE.
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
                        NusaAvatar(name = user.displayName, url = user.avatarUrl, gender = user.gender, seed = user.id, size = 100.dp)
                        if (uploading) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
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
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !uploading) { picker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Camera),
                            contentDescription = "Ganti foto",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                NusaTextButton(onClick = { picker.launch("image/*") }) {
                    Text("Ganti foto profil")
                }
            }

            SectionDivider()

            // Form input garis bawah gaya LINE — tanpa kartu/kotak.
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                NusaLineInput(
                    label = "Nama",
                    value = displayName,
                    onValueChange = { displayName = it },
                    counter = "${displayName.length}/40",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                NusaLineInput(
                    label = "Username",
                    value = username,
                    onValueChange = { if (it.length <= 20) username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' } },
                    supportingText = "Teman menemukanmu lewat @username",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                NusaLineInput(
                    label = "Nomor HP",
                    value = phone,
                    onValueChange = { phone = it },
                    placeholder = "08xxxxxxxxxx",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                NusaLineInput(
                    label = "Status message / bio",
                    value = bio,
                    onValueChange = { bio = it },
                    placeholder = "Tulis statusmu",
                    counter = "${bio.length}/160",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionDivider()

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                NusaLinePickerInput(
                    label = "Tanggal lahir",
                    value = if (birthday.isBlank()) "" else formatBirthdayDisplay(birthday),
                    placeholder = "Pilih tanggal lahir",
                    supportingText = birthdayAge(birthday)?.let { "$it tahun" },
                    trailingIconRes = NusaIcons.Calendar,
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Gender",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "" to "Tidak diisi",
                        "male" to "Laki-laki",
                        "female" to "Perempuan",
                        "other" to "Lainnya",
                    ).forEach { (value, label) ->
                        GenderChip(
                            label = label,
                            selected = gender == value,
                            onClick = { gender = value },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Tampil di Orang Terdekat", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Matikan agar lokasimu tidak ditemukan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NusaSwitch(checked = discoverable, onCheckedChange = { discoverable = it })
                }
            }

            if (localError != null) {
                Text(
                    text = localError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                NusaButton(
                    onClick = { save() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Simpan perubahan")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        NusaDatePickerDialog(
            initialDateMillis = parseBirthdayMillis(birthday),
            onDismiss = { showDatePicker = false },
            onConfirm = { millis ->
                birthday = formatBirthdayMillis(millis)
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun GenderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

private fun parseBirthdayMillis(value: String): Long? {
    if (value.isBlank()) return null
    return runCatching {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        format.parse(value.trim())?.time
    }.getOrNull()
}

private fun formatBirthdayMillis(millis: Long): String {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    return format.format(java.util.Date(millis))
}

private fun formatBirthdayDisplay(value: String): String {
    val millis = parseBirthdayMillis(value) ?: return value
    val format = java.text.SimpleDateFormat(
        "dd MMMM yyyy",
        java.util.Locale("id", "ID"),
    ).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    return format.format(java.util.Date(millis))
}

private fun birthdayAge(value: String): Int? {
    val millis = parseBirthdayMillis(value) ?: return null
    val birth = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = millis
    }
    val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    var age = now.get(java.util.Calendar.YEAR) - birth.get(java.util.Calendar.YEAR)
    if (
        now.get(java.util.Calendar.DAY_OF_YEAR) <
        birth.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        age--
    }
    return age.takeIf { it in 0..120 }
}
