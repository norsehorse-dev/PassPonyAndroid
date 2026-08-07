package com.passpony.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.ui.detail.TotpFormat
import com.passpony.android.ui.util.Clipboard
import kotlinx.coroutines.delay
import uniffi.pass_ffi.TotpCode
import uniffi.pass_ffi.entryTotp

private const val RED_THRESHOLD_SECONDS = 5uL
private val RING_STROKE_WIDTH = 4.dp
private val RING_DIAMETER = 36.dp

/**
 * Live TOTP code with a progress ring, re-derived every second from
 * already-decrypted [content] — never re-decrypts. Port of PassPony
 * iOS's TOTPRingView; Compose has no TimelineView, so a 1 s
 * LaunchedEffect loop stands in for it, ticking only while this
 * composable is on screen.
 */
@Composable
fun TotpRing(content: ByteArray) {
    var totp by remember(content) { mutableStateOf<TotpCode?>(null) }

    LaunchedEffect(content) {
        while (true) {
            totp = entryTotp(content, (System.currentTimeMillis() / 1000L).toULong())
            delay(1000)
        }
    }

    val code = totp ?: return
    val context = LocalContext.current
    val period = code.period.coerceAtLeast(1uL).toFloat()
    val fraction = code.secondsRemaining.toFloat() / period
    val ringColor =
        if (code.secondsRemaining <= RED_THRESHOLD_SECONDS) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(RING_DIAMETER)) {
            Canvas(modifier = Modifier.size(RING_DIAMETER)) {
                val strokePx = RING_STROKE_WIDTH.toPx()
                drawArc(
                    color = ringColor.copy(alpha = 0.25f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
            Text(code.secondsRemaining.toString(), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = TotpFormat.group(code.code),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = { Clipboard.copyEphemeral(context, code.code) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.entry_detail_copy))
        }
    }
}
