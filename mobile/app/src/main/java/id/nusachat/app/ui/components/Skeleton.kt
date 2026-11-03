package id.nusachat.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Skeleton shimmer reusable: tampilkan saat data dimuat agar layar tidak
 * kosong dan tidak perlu spinner di tengah layar.
 *
 * Contoh: ChatsScreen (daftar obrolan), MomentsScreen (feed),
 * ContactsScreen/NearbyScreen (daftar pengguna).
 */
@Composable
fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )
    return Brush.linearGradient(
        colors = listOf(
            base,
            base.copy(alpha = 0.45f),
            base,
        ),
        start = Offset(progress * 400f - 200f, 0f),
        end = Offset(progress * 400f + 200f, 200f),
    )
}

@Composable
fun SkeletonLine(
    widthFraction: Float = 1f,
    height: Dp = 14.dp,
    brush: Brush = shimmerBrush(),
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(7.dp))
            .background(brush),
    )
}

@Composable
fun SkeletonAvatar(size: Dp = 48.dp, brush: Brush = shimmerBrush()) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(brush),
    )
}

@Composable
fun SkeletonRow(
    avatarSize: Dp = 48.dp,
    lines: Int = 2,
    brush: Brush = shimmerBrush(),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonAvatar(size = avatarSize, brush = brush)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkeletonLine(widthFraction = 0.55f, brush = brush)
            if (lines > 1) SkeletonLine(widthFraction = 0.85f, height = 12.dp, brush = brush)
            if (lines > 2) SkeletonLine(widthFraction = 0.4f, height = 12.dp, brush = brush)
        }
    }
}

/** Daftar obrolan / kontak / nearby saat memuat. */
@Composable
fun SkeletonList(count: Int = 6, avatarSize: Dp = 48.dp) {
    val brush = shimmerBrush()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        userScrollEnabled = false,
    ) {
        items(count) { SkeletonRow(avatarSize = avatarSize, brush = brush) }
    }
}

/** Kartu momen saat feed dimuat. */
@Composable
fun MomentSkeleton(brush: Brush = shimmerBrush()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        SkeletonAvatar(size = 42.dp, brush = brush)
        Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
            SkeletonLine(widthFraction = 0.4f, brush = brush)
            Spacer(Modifier.height(8.dp))
            SkeletonLine(widthFraction = 0.9f, height = 12.dp, brush = brush)
            Spacer(Modifier.height(6.dp))
            SkeletonLine(widthFraction = 0.65f, height = 12.dp, brush = brush)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brush),
            )
        }
    }
}
