package com.passpony.android.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * CameraX analyzer that hands each frame's luminance plane to zxing and
 * reports the first QR code it decodes. Frames live only in the
 * ImageProxy CameraX owns: the Y plane is copied into a reusable buffer
 * for the decoder and nothing is ever written anywhere. zxing core is
 * pure Java (Apache-2.0), so this stays in common code for both flavors
 * with no Play services or ML Kit involved.
 *
 * [onText] is delivered on [mainExecutor]. After a hit the analyzer
 * pauses itself so the same code isn't reported on every following
 * frame; the screen calls [resume] if it rejects the payload and wants
 * to keep scanning.
 */
class QrAnalyzer(
    private val mainExecutor: Executor,
    private val onText: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private val active = AtomicBoolean(true)
    private var buffer = ByteArray(0)

    fun resume() {
        active.set(true)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!active.get()) return
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val needed = rowStride * image.height
            if (buffer.size < needed) buffer = ByteArray(needed)
            val source = plane.buffer
            source.rewind()
            source.get(buffer, 0, min(needed, source.remaining()))
            val luminance = PlanarYUVLuminanceSource(
                buffer, rowStride, image.height, 0, 0, image.width, image.height, false
            )
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text
            } catch (e: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (text != null && active.compareAndSet(true, false)) {
                mainExecutor.execute { onText(text) }
            }
        } finally {
            image.close()
        }
    }
}
