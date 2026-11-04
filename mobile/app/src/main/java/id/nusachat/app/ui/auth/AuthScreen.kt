package id.nusachat.app.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import id.nusachat.app.data.GoogleAuthHelper
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaProgress
import id.nusachat.app.ui.components.NusaTextField
import id.nusachat.app.ui.theme.NusaIcons
import kotlinx.coroutines.launch

private enum class AuthPage { WELCOME, LOGIN, REGISTER }

/**
 * Alur masuk ala LINE: sambutan (Login/Daftar) -> login Google atau
 * username/sandi -> daftar bertahap (Google -> nama -> avatar ->
 * sandi -> persetujuan).
 */
@Composable
fun AuthScreen(vm: AppViewModel) {
    var page by rememberSaveable { mutableStateOf(AuthPage.WELCOME) }
    val pending by vm.googlePending.collectAsStateWithLifecycle()
    // Login Google untuk akun baru -> lanjutkan ke isi profil.
    androidx.compose.runtime.LaunchedEffect(pending) {
        if (pending != null) page = AuthPage.REGISTER
    }

    // Akun Google belum punya akun NusaChat -> lompat ke isi profil.
    val regStep = rememberSaveable(pending != null) { mutableStateOf(if (pending != null) 1 else 0) }

    when (page) {
        AuthPage.WELCOME -> WelcomePage(
            onLogin = { page = AuthPage.LOGIN },
            onRegister = { vm.cancelGoogle(); regStep.value = 0; page = AuthPage.REGISTER },
        )
        AuthPage.LOGIN -> LoginPage(vm = vm, onBack = { page = AuthPage.WELCOME })
        AuthPage.REGISTER -> RegisterFlow(
            vm = vm,
            step = regStep.value,
            onStep = { regStep.value = it },
            onBack = {
                if (regStep.value == 0) {
                    vm.cancelGoogle()
                    page = AuthPage.WELCOME
                } else {
                    regStep.value -= 1
                }
            },
        )
    }
}

// ---------------------------------------------------------------- welcome

@Composable
private fun WelcomePage(onLogin: () -> Unit, onRegister: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.Chat),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(62.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Selamat Datang di NusaChat", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(
            "Nikmati pesan serta panggilan suara gratis, dan banyak lagi!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        NusaButton(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        ) {
            Text("Login", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            onClick = onRegister,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("Daftar", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- tombol Google

@Composable
private fun GoogleButton(label: String, loading: Boolean, onToken: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Surface(
        onClick = {
            if (busy || loading) return@Surface
            busy = true
            scope.launch {
                try {
                    val activity = context as? Activity ?: return@launch
                    onToken(GoogleAuthHelper.requestIdToken(activity))
                } catch (e: id.nusachat.app.data.GoogleCancelled) {
                    // Batal: diam saja.
                } catch (e: Exception) {
                    onError(e.message ?: "Gagal mengambil akun Google.")
                } finally {
                    busy = false
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (busy || loading) {
                NusaProgress(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                GoogleG()
                Spacer(Modifier.width(10.dp))
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

/** Markah "G" Google (lingkaran putih + huruf biru). */
@Composable
private fun GoogleG() {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("G", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color(0xFF4285F4))
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text("atau", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun AuthErrorBox(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

// ---------------------------------------------------------------- login

@Composable
private fun LoginPage(vm: AppViewModel, onBack: () -> Unit) {
    var identifier by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val loading by vm.authLoading.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        NusaIconButton(onClick = { vm.clearAuthError(); onBack() }) {
            Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
        }
        Spacer(Modifier.height(8.dp))
        Text("Login", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Masuk untuk lanjut mengobrol.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
        GoogleButton(label = "Lanjutkan dengan Google", loading = loading, onToken = vm::loginWithGoogle, onError = vm::authGoogleError)
        Spacer(Modifier.height(18.dp))
        OrDivider()
        Spacer(Modifier.height(18.dp))
        NusaTextField(
            value = identifier,
            onValueChange = { identifier = it },
            label = { Text("Nama pengguna atau nomor HP") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(12.dp))
        NusaTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Kata sandi") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                NusaIconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        painter = painterResource(id = if (passwordVisible) NusaIcons.Hidden else NusaIcons.Visible),
                        contentDescription = if (passwordVisible) "Sembunyikan" else "Tampilkan",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            AuthErrorBox(error.orEmpty())
        }
        Spacer(Modifier.height(18.dp))
        NusaButton(
            onClick = { vm.login(identifier, password) },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        ) {
            if (loading) NusaProgress(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Text("Masuk", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        var showForgot by rememberSaveable { mutableStateOf(false) }
        TextButton(
            onClick = { showForgot = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Lupa kata sandi?", color = MaterialTheme.colorScheme.primary)
        }
        if (showForgot) {
            ForgotPasswordSheet(vm = vm, onDismiss = { showForgot = false })
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Akun demo: nusa, sari, budi, dewi • password123",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgotPasswordSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    var login by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var sentToDev by rememberSaveable { mutableStateOf<String?>(null) }
    var done by rememberSaveable { mutableStateOf(false) }
    val loading by vm.authLoading.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()

    NusaBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Lupa kata sandi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            when {
                done -> {
                    Text(
                        "Kata sandi diganti. Silakan masuk dengan sandi barumu.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    NusaButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Tutup", fontWeight = FontWeight.Bold) }
                }
                step == 0 -> {
                    Text(
                        "Masukkan nama pengguna / nomor HP. Tautan reset dikirim (demo: token langsung tampil).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    NusaTextField(
                        value = login,
                        onValueChange = { login = it },
                        label = { Text("Nama pengguna atau nomor HP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(14.dp))
                    NusaButton(
                        onClick = {
                            vm.clearAuthError()
                            vm.forgotPassword(login) { devToken ->
                                sentToDev = devToken
                                step = 1
                            }
                        },
                        enabled = !loading && login.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (loading) NusaProgress(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else Text("Kirim tautan reset", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Text(
                        if (sentToDev != null) "Mode demo — token resetmu: $sentToDev" else "Masukkan token dari SMS/email dan sandi baru.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    NusaTextField(
                        value = token,
                        onValueChange = { token = it.trim() },
                        label = { Text("Token reset") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    NusaTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Kata sandi baru (min. 6)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(14.dp))
                    NusaButton(
                        onClick = {
                            vm.clearAuthError()
                            vm.resetPassword(token, newPassword) { done = true }
                        },
                        enabled = !loading && token.isNotBlank() && newPassword.length >= 6,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (loading) NusaProgress(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else Text("Ganti kata sandi", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- register bertahap

@Composable
private fun RegisterFlow(vm: AppViewModel, step: Int, onStep: (Int) -> Unit, onBack: () -> Unit) {
    val pending by vm.googlePending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pending) {
        if (pending != null && step == 0) onStep(1)
    }
    val loading by vm.authLoading.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()

    var displayName by rememberSaveable(pending?.name) { mutableStateOf(pending?.name.orEmpty()) }
    var username by rememberSaveable(pending?.email) {
        mutableStateOf(pending?.email?.substringBefore("@")?.filter { it.isLetterOrDigit() || it == '.' || it == '_' }?.lowercase().orEmpty())
    }
    var avatarUrl by rememberSaveable(pending?.picture) { mutableStateOf(pending?.picture.orEmpty()) }
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
    var password2 by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var agreeTerms by rememberSaveable { mutableStateOf(false) }
    var agreePrivacy by rememberSaveable { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            avatarUrl = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        NusaIconButton(onClick = { vm.clearAuthError(); onBack() }) {
            Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
        }
        Spacer(Modifier.height(8.dp))
        Text("Buat Akun", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Langkah ${minOf(step + 1, 4)} dari 4",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (minOf(step + 1, 4)) / 4f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.height(24.dp))

        when (step) {
            0 -> {
                Text("Daftar dengan akun Google-mu.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                GoogleButton(label = "Pilih akun Google", loading = loading, onToken = vm::loginWithGoogle, onError = vm::authGoogleError)
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    AuthErrorBox(error.orEmpty())
                }
            }
            1 -> {
                Text("Isi nama dan username.", style = MaterialTheme.typography.bodyMedium)
                if (pending != null) {
                    Text(
                        pending?.email.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                NusaTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Nama tampilan") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
                NusaTextField(
                    value = username,
                    onValueChange = { username = it.lowercase() },
                    label = { Text("Username") },
                    supportingText = { Text("4-20 huruf kecil, angka, titik, atau _") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    AuthErrorBox(error.orEmpty())
                }
                Spacer(Modifier.height(18.dp))
                StepButton(label = "Lanjut", enabled = displayName.isNotBlank() && username.isNotBlank(), onClick = { onStep(2) })
            }
            2 -> {
                Text("Pasang foto profil.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val model: Any? = pickedUri ?: avatarUrl.takeIf { it.isNotBlank() }
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            modifier = Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                displayName.trim().firstOrNull()?.uppercase() ?: "?",
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                NusaButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text("Pilih foto", fontWeight = FontWeight.SemiBold)
                }
                if (pickedUri != null || avatarUrl.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pakai foto Google",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally).clickable {
                            pickedUri = null
                            avatarUrl = pending?.picture.orEmpty()
                        },
                    )
                }
                Spacer(Modifier.height(18.dp))
                StepButton(
                    label = "Lanjut",
                    enabled = true,
                    onClick = {
                        vm.pendingAvatarUri = pickedUri
                        onStep(3)
                    },
                )
            }
            else -> {
                if (step == 3) {
                    Text("Buat kata sandi untuk akunmu.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    PasswordField(value = password, onValueChange = { password = it }, label = "Kata sandi", visible = passwordVisible, onToggle = { passwordVisible = !passwordVisible })
                    Spacer(Modifier.height(12.dp))
                    PasswordField(value = password2, onValueChange = { password2 = it }, label = "Ulangi kata sandi", visible = passwordVisible, onToggle = { passwordVisible = !passwordVisible })
                    if (password2.isNotBlank() && password != password2) {
                        Spacer(Modifier.height(8.dp))
                        Text("Kata sandi tidak sama.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        AuthErrorBox(error.orEmpty())
                    }
                    Spacer(Modifier.height(18.dp))
                    StepButton(
                        label = "Lanjut",
                        enabled = password.length >= 6 && password == password2,
                        onClick = { onStep(4) },
                    )
                } else {
                    Text("Satu langkah lagi.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            ConsentRow(checked = agreeTerms, onChecked = { agreeTerms = it }, title = "Syarat Layanan", subtitle = "Setuju menjadi pengguna NusaChat.")
                            ConsentRow(checked = agreePrivacy, onChecked = { agreePrivacy = it }, title = "Kebijakan Privasi", subtitle = "Pesan terenkripsi end-to-end; lokasi hanya untuk Orang Terdekat.")
                        }
                    }
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        AuthErrorBox(error.orEmpty())
                    }
                    Spacer(Modifier.height(18.dp))
                    NusaButton(
                        onClick = { vm.registerWithGoogle(username, displayName, password, avatarUrl) },
                        enabled = !loading && agreeTerms && agreePrivacy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    ) {
                        if (loading) NusaProgress(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else Text("Setuju & Buat Akun", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tolak",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally).clickable { vm.cancelGoogle(); onBack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    NusaButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, visible: Boolean, onToggle: () -> Unit) {
    NusaTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            NusaIconButton(onClick = onToggle) {
                Icon(
                    painter = painterResource(id = if (visible) NusaIcons.Hidden else NusaIcons.Visible),
                    contentDescription = if (visible) "Sembunyikan" else "Tampilkan",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun ConsentRow(checked: Boolean, onChecked: (Boolean) -> Unit, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChecked(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
