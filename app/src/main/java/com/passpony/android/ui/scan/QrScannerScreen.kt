package com.passpony.android.ui.scan

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.passpony.android.R
import com.passpony.android.ui.util.SecureScreenEffect
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

private const val REJECTION_SHOWN_MS = 2_500L
private const val CONFIRMATION_SHOWN_MS = 1_500L

/**
 * What the scanner shows after a decode that doesn't close it: an error
 * (wrong kind of code), or, since 2.0, a note about a multi-code export.
 * [accepted] marks a code that was taken as the next part of the export,
 * shown as a confirmation over the camera. [progress] has one flag per
 * code of the export, true where scanned, and stays on screen until the
 * scanner closes. Either way scanning resumes after a moment.
 */
data class ScanFeedback(
    val message: String,
    val isError: Boolean = true,
    val accepted: Boolean = false,
    val progress: List<Boolean>? = null,
)

/** Green that reads on both the camera overlay and the app's surfaces. */
internal val ScanConfirmGreen = Color(0xFF34C759)

/**
 * Full-screen QR scanner: CameraX preview plus a [QrAnalyzer] feeding
 * zxing. Hosted as a Dialog by the setup sheet, deliberately not a
 * navigation destination, so the decoded URI (a secret) stays in the
 * calling screen's Compose state and never travels through navigation
 * arguments or saved state.
 *
 * [onDecoded] validates a payload and returns null to accept it (the
 * caller closes the scanner) or a message to show while scanning
 * continues, so a wrong kind of QR code (HOTP, a Google Authenticator
 * export, a URL) is explained rather than silently ignored.
 */
@Composable
fun QrScannerDialog(
    onDecoded: (String) -> ScanFeedback?,
    onClose: () -> Unit,
    helpText: String? = null,
    nextHint: String? = null,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        QrScannerScreen(onDecoded = onDecoded, onClose = onClose, helpText = helpText, nextHint = nextHint)
    }
}

@Composable
fun QrScannerScreen(
    onDecoded: (String) -> ScanFeedback?,
    onClose: () -> Unit,
    helpText: String? = null,
    nextHint: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var rejection by remember { mutableStateOf<ScanFeedback?>(null) }
    var confirmation by remember { mutableStateOf<ScanFeedback?>(null) }
    var progress by remember { mutableStateOf<List<Boolean>?>(null) }
    var analyzer by remember { mutableStateOf<QrAnalyzer?>(null) }

    SecureScreenEffect()

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val qrAnalyzer = QrAnalyzer(ContextCompat.getMainExecutor(context)) { text ->
            val feedback = onDecoded(text)
            if (feedback != null) {
                feedback.progress?.let { progress = it }
                if (feedback.accepted) {
                    confirmation = feedback
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                        else HapticFeedbackConstants.VIRTUAL_KEY
                    )
                } else {
                    rejection = feedback
                }
            }
        }
        analyzer = qrAnalyzer
        providerFuture.addListener({
            val resolved = providerFuture.get()
            provider = resolved
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor, qrAnalyzer)
            resolved.unbindAll()
            camera = resolved.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            analyzerExecutor.shutdown()
            camera = null
        }
    }

    // A rejected code is shown briefly, then scanning resumes.
    LaunchedEffect(rejection) {
        if (rejection != null) {
            delay(REJECTION_SHOWN_MS)
            rejection = null
            analyzer?.resume()
        }
    }

    // A confirmed part of an export holds the camera long enough to move
    // on to the next code, so the same one isn't reported again at once.
    LaunchedEffect(confirmation) {
        if (confirmation != null) {
            delay(CONFIRMATION_SHOWN_MS)
            confirmation = null
            analyzer?.resume()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.otp_scan_close),
                    tint = Color.White
                )
            }
            Text(
                stringResource(R.string.otp_scan_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            val hasTorch = camera?.cameraInfo?.hasFlashUnit() == true
            if (hasTorch) {
                IconButton(onClick = {
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                }) {
                    Icon(
                        if (torchOn) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                        contentDescription = stringResource(R.string.otp_scan_torch),
                        tint = Color.White
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            progress?.let { done ->
                ScanProgressBar(
                    done = done,
                    doneColor = ScanConfirmGreen,
                    todoColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            val feedback = rejection
            val idle = if (progress != null && nextHint != null) nextHint
                else helpText ?: stringResource(R.string.otp_scan_help)
            Text(
                feedback?.message ?: idle,
                color = if (feedback?.isError == true) MaterialTheme.colorScheme.error else Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        confirmation?.let { shown ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ScanConfirmGreen,
                    modifier = Modifier.size(72.dp)
                )
                Text(
                    shown.message,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

/**
 * One segment per code of a multi-code export, filled where that code is
 * scanned, so it shows which codes are left as well as how many.
 */
@Composable
internal fun ScanProgressBar(
    done: List<Boolean>,
    doneColor: Color,
    todoColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (scanned in done) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(if (scanned) doneColor else todoColor, RoundedCornerShape(3.dp))
            )
        }
    }
}
