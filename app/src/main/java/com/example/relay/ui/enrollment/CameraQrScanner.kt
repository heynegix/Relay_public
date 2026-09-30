package com.example.relay.ui.enrollment

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Analysis resolution previewed to the operator; kept modest for low-end disaster devices. */
private const val QR_ANALYSIS_WIDTH = 1280
private const val QR_ANALYSIS_HEIGHT = 720
private val QR_ANALYSIS_RESOLUTION = Size(QR_ANALYSIS_WIDTH, QR_ANALYSIS_HEIGHT)

/**
 * CameraX-based QR code scanner composable.
 *
 * Security invariants:
 * - Scanned content is NEVER logged
 * - Only calls [onQrDetected] with the raw text; does NOT persist anything
 * - Caller must show confirmation before enrollment
 *
 * If CameraX initialization fails, the composable remains blank and the caller should
 * offer paste-only input as a fallback.
 */
@Composable
fun CameraQrScanner(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Stops consecutive decoded frames from re-delivering the same code before the
    // caller closes the camera. A fresh composition gets a fresh gate.
    val delivered = remember { AtomicBoolean(false) }

    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    // Bind exactly once per composition. Binding inside AndroidView's update lambda
    // re-ran unbindAll()+bind on every recomposition, visibly churning the camera.
    LaunchedEffect(Unit) {
        val cameraProvider = awaitCameraProvider(context) ?: return@LaunchedEffect
        val reader = createQrReader()
        bindQrCamera(cameraProvider, lifecycleOwner, previewView, executor) { imageProxy ->
            processImage(imageProxy, reader, delivered, onQrDetected)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Resolves the CameraX provider; null means initialization failed and the view stays blank. */
private suspend fun awaitCameraProvider(context: android.content.Context): ProcessCameraProvider? =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = try {
                    future.get()
                } catch (_: Exception) {
                    // Initialization failed (no camera, policy, etc.); leave the view blank.
                    null
                }
                if (continuation.isActive) continuation.resume(provider)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

private fun bindQrCamera(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    executor: java.util.concurrent.Executor,
    onFrame: ImageAnalysis.Analyzer,
) {
    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }
    val imageAnalysis = ImageAnalysis.Builder()
        .setTargetResolution(QR_ANALYSIS_RESOLUTION)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { analysis -> analysis.setAnalyzer(executor, onFrame) }
    runCatching {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
        )
    }
}

/** Single reader reused across frames; ZXing readers are stateful and not thread-safe. */
private fun createQrReader(): MultiFormatReader = MultiFormatReader().apply {
    setHints(mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    ))
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImage(
    imageProxy: ImageProxy,
    reader: MultiFormatReader,
    delivered: AtomicBoolean,
    onQrDetected: (String) -> Unit,
) {
    try {
        val image = imageProxy.image ?: return
        val plane = image.planes[0]
        if (plane.pixelStride != 1) return
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        // Many camera providers pad rows (rowStride > width). Handing ZXing a width-based
        // stride on such devices shifts every row and makes codes undecodable; passing the
        // real stride as dataWidth keeps rows aligned (ZXing crops to width itself).
        val source = if (plane.rowStride > image.width && data.size >= plane.rowStride * image.height) {
            PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0, 0,
                image.width,
                image.height,
                false,
            )
        } else {
            PlanarYUVLuminanceSource(
                data,
                image.width,
                image.height,
                0, 0,
                image.width,
                image.height,
                false,
            )
        }
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decodeWithState(bitmap)
        val text = result.text
        if (!text.isNullOrBlank() && delivered.compareAndSet(false, true)) {
            onQrDetected(text)
        }
    } catch (_: Exception) {
        // No QR code found in this frame — normal during scanning.
    } finally {
        reader.reset()
        imageProxy.close()
    }
}
