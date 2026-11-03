package id.nusachat.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaLineInput
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Halaman Ganti Kata Sandi — halaman penuh (bukan sheet),
 * memakai input garis bawah gaya LINE hasil redesign.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    onBack: () -> Unit,
    onConfirm: (old: String, new: String) -> Unit,
) {
    var old by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showOld by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    fun submit() {
        error = when {
            old.isBlank() -> "Sandi lama wajib diisi."
            newPass.length < 6 -> "Sandi baru minimal 6 karakter."
            newPass != confirm -> "Konfirmasi tidak cocok."
            newPass == old -> "Sandi baru harus berbeda dari sandi lama."
            else -> null
        }
        if (error == null) onConfirm(old, newPass)
    }

    // Header sebaris — halaman ini di dalam Scaffold utama, TopAppBar bikin dobel.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NusaIconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(id = NusaIcons.Back),
                    contentDescription = "Kembali",
                )
            }
            Text(
                text = "Ganti Kata Sandi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Masukkan sandi lama, lalu buat sandi baru. Setelah diganti kamu akan diminta masuk ulang.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            PasswordLineInput(
                label = "Sandi lama",
                value = old,
                onValueChange = { old = it },
                visible = showOld,
                onToggleVisible = { showOld = !showOld },
            )
            Spacer(Modifier.height(20.dp))
            PasswordLineInput(
                label = "Sandi baru",
                value = newPass,
                onValueChange = { newPass = it },
                visible = showNew,
                onToggleVisible = { showNew = !showNew },
                supportingText = "Minimal 6 karakter",
            )
            Spacer(Modifier.height(20.dp))
            PasswordLineInput(
                label = "Ulangi sandi baru",
                value = confirm,
                onValueChange = { confirm = it },
                visible = showConfirm,
                onToggleVisible = { showConfirm = !showConfirm },
                isError = error != null && confirm.isNotEmpty() && newPass != confirm,
            )

            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(28.dp))
            NusaButton(
                onClick = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Ganti kata sandi")
            }
        }
    }
}

@Composable
private fun PasswordLineInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    NusaLineInput(
        label = label,
        value = value,
        onValueChange = onValueChange,
        supportingText = supportingText,
        isError = isError,
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            NusaIconButton(onClick = onToggleVisible) {
                Icon(
                    painter = painterResource(
                        id = if (visible) NusaIcons.Visible else NusaIcons.Hidden,
                    ),
                    contentDescription = if (visible) "Sembunyikan sandi" else "Tampilkan sandi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
