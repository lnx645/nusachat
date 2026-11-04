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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import id.nusachat.app.data.WalletRequestItem
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaLineInput
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.PinSheet
import id.nusachat.app.ui.components.SectionLabel
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
    val hasPin by vm.walletHasPin.collectAsStateWithLifecycle()
    val incoming by vm.walletIncoming.collectAsStateWithLifecycle()
    val outgoing by vm.walletOutgoing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var noteText by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<User?>(null) }
    var showPinSheet by remember { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf("set") } // set | transfer | pay
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingPin by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadWalletFull() }
    BackHandler(onBack = onBack)

    fun doTransfer(pin: String?) {
        val target = picked ?: return
        val amount = amountText.toLongOrNull() ?: return
        vm.walletTransferPin(
            target, amount, noteText, pin,
            onPinRequired = {
                pendingAction = { doTransfer(pendingPin) }
                pinMode = "transfer"
                showPinSheet = true
            },
            onDone = {
                amountText = ""
                noteText = ""
                picked = null
                pendingAction = null
                pendingPin = null
            },
        )
    }

    fun doPay(item: WalletRequestItem, pin: String?) {
        vm.payWalletRequest(
            item, pin,
            onPinRequired = {
                pendingAction = { doPay(item, pendingPin) }
                pinMode = "pay"
                showPinSheet = true
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()),
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
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = if (hasPin) NusaIcons.Lock else NusaIcons.Hidden),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (hasPin) "PIN aktif" else "Belum ada PIN",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                NusaTextButton(onClick = { pinMode = "set"; showPinSheet = true }) {
                    Text(if (hasPin) "Ganti PIN" else "Buat PIN")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Top Up", "Kirim", "Tagih").forEachIndexed { index, label ->
                    NusaButton(
                        onClick = { tab = index },
                        modifier = Modifier.weight(1f),
                        colors = tabColors(tab == index),
                    ) { Text(label) }
                }
            }
        }
        when (tab) {
            0 -> Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
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
            1 -> SendCoinsForm(
                friends = friends,
                picked = picked,
                onPick = { picked = it },
                amountText = amountText,
                onAmount = { amountText = it.filter(Char::isDigit).take(9) },
                noteText = noteText,
                onNote = { if (it.length <= 200) noteText = it },
                hasPin = hasPin,
                onSubmit = { pin -> doTransfer(pin) },
            )
            else -> RequestCoinsForm(
                friends = friends,
                picked = picked,
                onPick = { picked = it },
                amountText = amountText,
                onAmount = { amountText = it.filter(Char::isDigit).take(9) },
                noteText = noteText,
                onNote = { if (it.length <= 200) noteText = it },
                onSubmit = {
                    val target = picked ?: return@RequestCoinsForm
                    val amount = amountText.toLongOrNull() ?: return@RequestCoinsForm
                    vm.requestMoney(target, amount, noteText)
                    amountText = ""
                    noteText = ""
                },
            )
        }

        if (incoming.isNotEmpty()) {
            SectionLabel("Tagihan masuk • ${incoming.size}")
            incoming.forEach { item ->
                RequestRow(
                    item = item,
                    incoming = true,
                    onPay = { doPay(item, null) },
                    onDecline = { vm.declineWalletRequest(item) },
                    onCancel = {},
                )
            }
        }
        if (outgoing.isNotEmpty()) {
            SectionLabel("Tagihan keluar • ${outgoing.size}")
            outgoing.forEach { item ->
                RequestRow(
                    item = item,
                    incoming = false,
                    onPay = {},
                    onDecline = {},
                    onCancel = { vm.cancelWalletRequest(item) },
                )
            }
        }
        Text(
            text = "Riwayat",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        txns.forEach { txn ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (txn.kind) {
                            "topup" -> "Top up"
                            "request" -> if (txn.direction == "out") "Bayar tagihan ${txn.peerName ?: ""}" else "Tagihan dibayar ${txn.peerName ?: ""}"
                            "redpacket_send" -> "Amplop merah terkirim"
                            "redpacket" -> if (txn.direction == "out") "Amplop diklaim ${txn.peerName ?: ""}" else "Amplop dari ${txn.peerName ?: ""}"
                            "redpacket_refund" -> "Sisa amplop kembali"
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
        Spacer(Modifier.height(24.dp))
    }

    if (showPinSheet) {
        if (pinMode == "set") {
            PinSheet(
                title = if (hasPin) "Ganti PIN" else "Buat PIN dompet",
                subtitle = "PIN 4-6 digit melindungi setiap pengeluaran koin.",
                requireOld = hasPin,
                onSubmit = { pin, oldPin ->
                    showPinSheet = false
                    vm.setWalletPin(pin, oldPin.ifBlank { null })
                },
                onDismiss = { showPinSheet = false },
            )
        } else {
            PinSheet(
                title = "PIN dompet",
                subtitle = "Masukkan PIN untuk melanjutkan.",
                confirmLabel = "Lanjut",
                onSubmit = { pin, _ ->
                    showPinSheet = false
                    pendingPin = pin
                    pendingAction?.invoke()
                },
                onDismiss = { showPinSheet = false; pendingAction = null; pendingPin = null },
            )
        }
    }
}

@Composable
private fun FriendChips(friends: List<User>, picked: User?, onPick: (User) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(friends, key = { it.id }) { friend ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (picked?.id == friend.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onPick(friend) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NusaAvatar(name = friend.displayName, url = friend.avatarUrl, gender = friend.gender, seed = friend.id, size = 28.dp)
                Spacer(Modifier.width(6.dp))
                Text(friend.familiarName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SendCoinsForm(
    friends: List<User>,
    picked: User?,
    onPick: (User) -> Unit,
    amountText: String,
    onAmount: (String) -> Unit,
    noteText: String,
    onNote: (String) -> Unit,
    hasPin: Boolean,
    onSubmit: (String?) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Ke teman", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FriendChips(friends, picked, onPick)
        Spacer(Modifier.height(8.dp))
        NusaLineInput(
            label = "Nominal",
            value = amountText,
            onValueChange = onAmount,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        NusaLineInput(
            label = "Catatan (opsional)",
            value = noteText,
            onValueChange = onNote,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasPin) {
            Spacer(Modifier.height(8.dp))
            NusaLineInput(
                label = "PIN dompet",
                value = pin,
                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        NusaButton(
            onClick = { onSubmit(pin.ifBlank { null }); pin = "" },
            enabled = picked != null && (amountText.toLongOrNull() ?: 0L) > 0 && (!hasPin || pin.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Kirim koin") }
    }
}

@Composable
private fun RequestCoinsForm(
    friends: List<User>,
    picked: User?,
    onPick: (User) -> Unit,
    amountText: String,
    onAmount: (String) -> Unit,
    noteText: String,
    onNote: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Tagih ke teman", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FriendChips(friends, picked, onPick)
        Spacer(Modifier.height(8.dp))
        NusaLineInput(
            label = "Nominal",
            value = amountText,
            onValueChange = onAmount,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        NusaLineInput(
            label = "Catatan (opsional)",
            value = noteText,
            onValueChange = onNote,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        NusaButton(
            onClick = onSubmit,
            enabled = picked != null && (amountText.toLongOrNull() ?: 0L) > 0,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Kirim tagihan") }
    }
}

@Composable
private fun RequestRow(
    item: WalletRequestItem,
    incoming: Boolean,
    onPay: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NusaAvatar(
                name = (if (incoming) item.requester else item.target).displayName,
                url = (if (incoming) item.requester else item.target).avatarUrl,
                size = 38.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (incoming) "${item.requester.familiarName} menagih ${formatKoin(item.amount)} koin"
                    else "Menagih ${item.target.familiarName} ${formatKoin(item.amount)} koin",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (!item.note.isNullOrBlank()) {
                    Text(item.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (incoming) {
                NusaTextButton(onClick = onDecline) { Text("Tolak", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                NusaButton(onClick = onPay, modifier = Modifier.height(40.dp)) { Text("Bayar") }
            } else {
                NusaTextButton(onClick = onCancel) { Text("Batalkan", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
