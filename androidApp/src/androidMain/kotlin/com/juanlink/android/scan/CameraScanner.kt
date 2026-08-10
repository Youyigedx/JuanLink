package com.juanlink.android.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.juanlink.core.qr.QrBitmap
import com.juanlink.core.qr.QrCodec
import com.juanlink.composeui.theme.Palette
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX 二维码扫描覆盖层。
 * - RGBA_8888 输出，按 rowStride/pixelStride 逐行转 ARGB IntArray 交给 QrCodec 解码
 * - 400ms 节流；命中即回调 onResult 并自动关闭
 */
@Composable
fun CameraScanner(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val granted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var hasPermission by remember { mutableStateOf(granted) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 分析器在线程池串行执行；仅最近一帧被保留（KEEP_ONLY_LATEST）
    val lastScanMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val scanning = remember { AtomicBoolean(true) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        val listener = Runnable {
            runCatching {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val now = System.currentTimeMillis()
                    val qr = if (scanning.get() && now - lastScanMs.get() >= 400) {
                        decodeQr(image)
                    } else {
                        null
                    }
                    image.close()
                    if (qr != null) {
                        lastScanMs.set(now)
                        if (scanning.compareAndSet(true, false)) {
                            onResult(qr)
                        }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            scanning.set(false)
            runCatching { cameraProviderFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        // 扫描框引导
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(36.dp),
        ) {
            Text("对准对方屏幕上的二维码", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        }
        // 关闭按钮
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.25f))
                .clickable(onClick = onClose)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("×", color = Color.White, fontSize = 20.sp)
        }
        // 权限未授予提示
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("需要相机权限才能扫码", color = Color.White, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.ZhuQing)
                        .clickable { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("授予权限", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

/** RGBA_8888 → ARGB IntArray → QrCodec 解码 */
private fun decodeQr(image: androidx.camera.core.ImageProxy): String? {
    val plane = image.planes.getOrNull(0) ?: return null
    val buffer = plane.buffer
    val w = image.width
    val h = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val argb = IntArray(w * h)
    var rowStart = 0
    for (y in 0 until h) {
        var offset = rowStart
        for (x in 0 until w) {
            val r = buffer.get(offset).toInt() and 0xFF
            val g = buffer.get(offset + 1).toInt() and 0xFF
            val b = buffer.get(offset + 2).toInt() and 0xFF
            val a = buffer.get(offset + 3).toInt() and 0xFF
            argb[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            offset += pixelStride
        }
        rowStart += rowStride
    }
    return QrCodec.decode(QrBitmap(w, h, argb))
}
