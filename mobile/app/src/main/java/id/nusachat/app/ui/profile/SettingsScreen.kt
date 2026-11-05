package id.nusachat.app.ui.profile

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.BuildConfig
import id.nusachat.app.data.MediaHelper
import id.nusachat.app.data.PushManager
import id.nusachat.app.data.SettingsStore
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaSwitch
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.SectionLabel
import id.nusachat.app.ui.components.formatListTime
import id.nusachat.app.ui.i18n.applyAppLocale
import id.nusachat.app.ui.i18n.findActivity
import id.nusachat.app.ui.i18n.t
import id.nusachat.app.ui.theme.NusaIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenBackup: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = t()
    val themeMode by vm.settings.themeMode.collectAsStateWithLifecycle()
    val language by vm.settings.language.collectAsStateWithLifecycle()
    val fontScale by vm.settings.fontScale.collectAsStateWithLifecycle()
    val notifMsg by vm.settings.notifMessages.collectAsStateWithLifecycle()
    val notifMoments by vm.settings.notifMoments.collectAsStateWithLifecycle()
    val notifSound by vm.settings.notifSound.collectAsStateWithLifecycle()
    val notifVibrate by vm.settings.notifVibrate.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sessionsLoading by vm.sessionsLoading.collectAsStateWithLifecycle()
    val reports by vm.myReports.collectAsStateWithLifecycle()
    val connected by vm.realtimeConnected.collectAsStateWithLifecycle()
    var cacheBytes by remember { mutableStateOf(-1L) }
    var confirmCache by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.loadSessions()
        vm.loadReports()
        cacheBytes = MediaHelper.cacheSize(context)
    }

    Scaffold(
        topBar = {
            NusaTopAppBar(
                title = { Text(s.settings, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = s.back)
                    }
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
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel(s.sectionAppearance)
            ThemeRow(
                selected = themeMode,
                onSelect = { vm.settings.setThemeMode(it) },
            )
            SectionLabel(s.sectionLanguage)
            LanguageRow(
                selected = language,
                onSelect = {
                    if (it != language) {
                        vm.settings.setLanguage(it)
                        applyAppLocale(it)
                        context.findActivity()?.recreate()
                    }
                },
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Font),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        s.fontSize,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(fontScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = fontScale,
                    onValueChange = { vm.settings.setFontScale(it) },
                    valueRange = 0.85f..1.3f,
                    steps = 8,
                )
            }

            SectionLabel(s.sectionNotifications)
            SwitchRow(
                icon = NusaIcons.Chat,
                title = s.notifChatWallet,
                checked = notifMsg,
                onChecked = { vm.settings.setNotifMessages(it) },
            )
            SwitchRow(
                icon = NusaIcons.Photo,
                title = s.notifMoments,
                checked = notifMoments,
                onChecked = { vm.settings.setNotifMoments(it) },
            )
            SwitchRow(
                icon = NusaIcons.Bell,
                title = s.notifSound,
                checked = notifSound,
                onChecked = {
                    vm.settings.setNotifSound(it)
                    PushManager.createChannels(context)
                },
            )
            SwitchRow(
                icon = NusaIcons.Bell,
                title = s.notifVibrate,
                checked = notifVibrate,
                onChecked = {
                    vm.settings.setNotifVibrate(it)
                    PushManager.createChannels(context)
                },
            )

            SectionLabel("${s.sectionDevices} • ${sessions.size}")
            if (sessionsLoading && sessions.isEmpty()) {
                Text(
                    s.sessionsLoading,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else if (sessions.isEmpty()) {
                Text(
                    s.sessionsEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                sessions.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.PhoneDevice),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.device?.takeIf { it.isNotBlank() } ?: s.deviceUnknown,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${s.activeSince} ${formatListTime(item.lastUsedAt ?: item.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NusaTextButton(onClick = { revokeTarget = item.jti }) {
                            Text(s.revoke, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 50.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            SectionLabel(s.sectionStorage)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Database),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cadangan lokal (.updb)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "File terenkripsi di HP Anda — tanpa server",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NusaTextButton(onClick = onOpenBackup) { Text("Buka") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Database),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.appCache, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (cacheBytes < 0) s.calculating else MediaHelper.formatBytes(cacheBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NusaTextButton(onClick = { confirmCache = true }) { Text(s.clear) }
            }

            SectionLabel("${s.sectionMyReports} • ${reports.size}")
            if (reports.isEmpty()) {
                Text(
                    s.reportsEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                reports.take(10).forEach { report ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Flag),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${report.targetType} #${report.targetId} • ${report.reason}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${report.status} • ${formatListTime(report.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionLabel(s.sectionServer)
            InfoLine(s.realtimeStatus, if (connected) s.connected else s.disconnected)
            InfoLine("API", BuildConfig.API_BASE_URL)
            InfoLine("WebSocket", BuildConfig.WS_URL)
            Spacer(Modifier.height(24.dp))
        }
    }

    revokeTarget?.let { jti ->
        NusaConfirmSheet(
            title = s.revokeTitle,
            text = s.revokeText,
            confirmLabel = s.revoke,
            danger = true,
            onConfirm = {
                sessions.firstOrNull { it.jti == jti }?.let { vm.revokeSession(it) }
                revokeTarget = null
            },
            onDismiss = { revokeTarget = null },
        )
    }

    if (confirmCache) {
        NusaConfirmSheet(
            title = s.clearCacheTitle,
            text = s.clearCacheText,
            confirmLabel = s.clear,
            onConfirm = {
                confirmCache = false
                scope.launch {
                    MediaHelper.clearCache(context)
                    cacheBytes = MediaHelper.cacheSize(context)
                }
            },
            onDismiss = { confirmCache = false },
        )
    }
}

@Composable
private fun ThemeRow(selected: String, onSelect: (String) -> Unit) {
    val s = t()
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        listOf(
            Triple(SettingsStore.THEME_SYSTEM, s.themeAuto, NusaIcons.Settings),
            Triple(SettingsStore.THEME_LIGHT, s.themeLight, NusaIcons.Sun),
            Triple(SettingsStore.THEME_DARK, s.themeDark, NusaIcons.Moon),
        ).forEach { (mode, label, icon) ->
            androidx.compose.material3.FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(label) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = icon),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun LanguageRow(selected: String, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        listOf(
            SettingsStore.LANG_ID to "Indonesia",
            SettingsStore.LANG_EN to "English",
        ).forEach { (code, label) ->
            androidx.compose.material3.FilterChip(
                selected = selected == code,
                onClick = { onSelect(code) },
                label = { Text(label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(icon: Int, title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        NusaSwitch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
