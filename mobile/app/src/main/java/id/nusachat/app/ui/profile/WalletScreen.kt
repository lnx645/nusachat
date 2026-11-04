package id.nusachat.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaLineInput
import id.nusachat.app.ui.theme.NusaIcons
import java.text.NumberFormat
import java.util.Locale

private fun formatKoin(amount: Long): String =
    NumberFormat.getNumberInstance(Locale("in", "ID")).format(amount)

@Composable
fun WalletScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val balance by vm.walletBalance.collectAsStateWithLifecycle()
    val txns by vm.walletTxns.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var noteText by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<User?>(null) }

    LaunchedEffect(Unit) { vm.loadWallet() }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
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
            Text("Dompet Saya", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Saldo koin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${formatKoin(balance)} koin",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NusaButton(
                    onClick = { tab = 0 },
                    modifier = Modifier.weight(1f),
                    colors = tabColors(tab == 0),
                ) { Text("Top Up") }
                NusaButton(
                    onClick = { tab = 1 },
                    modifier = Modifier.weight(1f),
                    colors = tabColors(tab == 1),
                ) { Text("Kirim") }
            }
        }
        if (tab == 0) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Nominal top up (koin demo, bukan uang sungguhan)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                listOf(10000L, 25000L, 50000L, 100000L).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { nominal ->
                            NusaButton(
                                onClick = { vm.walletTopup(nominal) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) { Text(formatKoin(nominal), fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Ke teman", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(friends, key = { it.id }) { friend ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (picked?.id == friend.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { picked = friend }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NusaAvatar(name = friend.displayName, url = friend.avatarUrl, gender = friend.gender, seed = friend.id, size = 28.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(friend.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                NusaLineInput(
                    label = "Nominal",
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit).take(9) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                NusaLineInput(
                    label = "Catatan (opsional)",
                    value = noteText,
                    onValueChange = { if (it.length <= 200) noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                NusaButton(
                    onClick = {
                        val target = picked ?: return@NusaButton
                        val amount = amountText.toLongOrNull() ?: return@NusaButton
                        vm.walletTransfer(target, amount, noteText)
                        amountText = ""
                        noteText = ""
                    },
                    enabled = picked != null && (amountText.toLongOrNull() ?: 0L) > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Kirim koin") }
            }
        }
        Text(
            text = "Riwayat",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(txns, key = { it.id }) { txn ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (txn.kind) {
                                "topup" -> "Top up"
                                else -> if (txn.direction == "out") "Kirim ke ${txn.peerName ?: "teman"}" else "Terima dari ${txn.peerName ?: "teman"}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (!txn.note.isNullOrBlank()) {
                            Text(txn.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        text = (if (txn.direction == "out") "-" else "+") + formatKoin(txn.amount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (txn.direction == "out") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun tabColors(selected: Boolean) = if (selected) {
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
} else {
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}
