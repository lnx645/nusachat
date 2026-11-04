package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.data.Message
import id.nusachat.app.data.User
import id.nusachat.app.ui.components.formatClockTime

/**
 * Render event sistem terstruktur: {"sys":kode,"by":id,"targets":[id],
 * "names":{id:nama},"title"?,"role"?}. Nama diambil LIVE dari anggota
 * (ganti nama tercermin di riwayat); snapshot "names" hanya cadangan
 * bila user tak dikenal lagi. Badan plaintext lama = fallback apa adanya.
 */
fun renderSystemText(body: String?, liveNames: Map<Long, String>): String {
    val raw = body?.trim().orEmpty()
    if (!raw.startsWith("{\"sys\":")) return raw
    return runCatching {
        // JSON manual (tanpa org.json) agar bisa di-unit-test di JVM biasa;
        // bentuk kabel tetap dan sederhana — lihat sysEvent() di backend.
        val sys = sysStr(raw, "sys") ?: return raw
        val byId = sysLong(raw, "by")
        if (byId <= 0) return raw
        val snap = sysStrMap(raw, "names")
        fun name(id: Long): String =
            liveNames[id]?.takeIf { it.isNotBlank() }
                ?: snap[id.toString()]?.takeIf { it.isNotBlank() }
                ?: "#$id"
        val by = name(byId)
        val targets = sysLongArray(raw, "targets")
        val label = when (targets.size) {
            0 -> ""
            1 -> name(targets[0])
            2 -> "${name(targets[0])} dan ${name(targets[1])}"
            3 -> "${name(targets[0])}, ${name(targets[1])} dan ${name(targets[2])}"
            else -> "${name(targets[0])}, ${name(targets[1])} dan ${targets.size - 2} lainnya"
        }
        when (sys) {
            "group_create" -> "$by membuat grup \"${sysStr(raw, "title").orEmpty()}\""
            "member_add" -> "$by menambahkan $label"
            "member_leave" -> "$by keluar"
            "member_remove" -> "$by mengeluarkan $label"
            "owner_transfer" -> "$by mengalihkan kepemilikan grup ke $label"
            "join_link" -> "$by bergabung via tautan undangan"
            "approve_join" -> "$by menyetujui $label bergabung"
            "role_change" -> if (sysStr(raw, "role") == "admin") {
                "$by mengangkat $label menjadi admin"
            } else {
                "$by mencabut admin $label"
            }
            "info_update" -> "$by mengubah info grup"
            "settings_update" -> "$by mengubah pengaturan grup"
            else -> "$by memperbarui grup"
        }
    }.getOrDefault(raw)
}

/** Ekstraktor JSON seperlunya untuk event sistem (string/angka/array/objek). */
private fun sysValueStart(src: String, key: String): Int {
    val i = src.indexOf("\"$key\":")
    if (i < 0) return -1
    return i + key.length + 3
}

private fun sysReadString(src: String, from: Int): String? {
    if (from < 0 || from >= src.length || src[from] != '"') return null
    val sb = StringBuilder()
    var i = from + 1
    while (i < src.length) {
        val c = src[i++]
        when (c) {
            '"' -> return sb.toString()
            '\\' -> {
                if (i >= src.length) return null
                when (val e = src[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > src.length) return null
                        sb.append(src.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                        i += 4
                    }
                    else -> return null
                }
            }
            else -> sb.append(c)
        }
    }
    return null
}

private fun sysStr(src: String, key: String): String? = sysReadString(src, sysValueStart(src, key))

private fun sysLong(src: String, key: String): Long {
    val from = sysValueStart(src, key)
    if (from < 0) return 0L
    var i = from
    while (i < src.length && (src[i].isDigit() || (i == from && src[i] == '-'))) i++
    return src.substring(from, i).toLongOrNull() ?: 0L
}

private fun sysLongArray(src: String, key: String): List<Long> {
    val from = sysValueStart(src, key)
    if (from < 0 || from >= src.length || src[from] != '[') return emptyList()
    val end = src.indexOf(']', from)
    if (end < 0) return emptyList()
    return src.substring(from + 1, end).split(",")
        .mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() }?.toLongOrNull() }
}

private val SYS_PAIR = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun sysUnesc(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        if (c == '\\' && i < s.length) {
            when (val e = s[i++]) {
                '"', '\\', '/' -> sb.append(e)
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'u' -> {
                    sb.append(s.substring(i, (i + 4).coerceAtMost(s.length)).toIntOrNull(16)?.toChar() ?: '?')
                    i += 4
                }
                else -> sb.append(e)
            }
        } else sb.append(c)
    }
    return sb.toString()
}

private fun sysStrMap(src: String, key: String): Map<String, String> {
    val from = sysValueStart(src, key)
    if (from < 0 || from >= src.length || src[from] != '{') return emptyMap()
    // Rentang objek: kurung tutup yang seimbang (abaikan dalam string).
    var depth = 0
    var instr = false
    var esc = false
    var end = -1
    for (i in from until src.length) {
        val c = src[i]
        if (instr) {
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') instr = false
        } else {
            when (c) {
                '"' -> instr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
    }
    if (end < 0) return emptyMap()
    return SYS_PAIR.findAll(src.substring(from, end + 1)).associate {
        sysUnesc(it.groupValues[1]) to sysUnesc(it.groupValues[2])
    }
}

/**
 * Bubble sistem ala WhatsApp: pill center yang rapi meski teks panjang
 * (mis. tambah 10 anggota). Teks wrapping, jam menempel di akhir baris.
 */
@Composable
fun SystemEventBubble(message: Message, members: List<User> = emptyList(), wallpaperDark: Boolean) {
    val time = formatClockTime(message.createdAt)
    val liveNames = remember(members) { members.associate { it.id to it.displayName } }
    val text = remember(message.body, liveNames) { renderSystemText(message.body, liveNames) }
    if (text.isBlank()) return
    val (pillBg, pillFg) = id.nusachat.app.ui.theme.chatPillColors(wallpaperDark)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val inline = if (time.isNotBlank()) mapOf("time" to InlineTextContent(
            Placeholder(width = 36.sp, height = 11.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = pillFg.copy(alpha = 0.65f),
            )
        }) else emptyMap()
        val annotated = buildAnnotatedString {
            append(text)
            if (time.isNotBlank()) {
                append("  ")
                appendInlineContent("time", "[$time]")
            }
        }
        Text(
            text = annotated,
            inlineContent = inline,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = pillFg,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(pillBg.copy(alpha = 0.88f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
