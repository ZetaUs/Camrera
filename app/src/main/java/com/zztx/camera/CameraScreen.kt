package com.zztx.camera

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("InlinedApi")
private fun createPhotoMediaStoreUri(context: Context, fileName: String): Uri? {
    fun tryInsert(relativePath: String?): Uri? {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            context.contentResolver.insert(collection, values)
        }.getOrNull()
    }

    if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
        return null
    }

    // 1. 优先用户指定根目录 Photo
    tryInsert("Photo")?.let { return it }
    // 2. Fallback: 规范公共目录 Pictures/Photo
    tryInsert("${Environment.DIRECTORY_PICTURES}/Photo")?.let { return it }
    // 3. Fallback: 规范公共目录 DCIM/Photo
    tryInsert("${Environment.DIRECTORY_DCIM}/Photo")?.let { return it }
    // 4. Fallback: DCIM (必定存在)
    return tryInsert(Environment.DIRECTORY_DCIM)
}

private fun markMediaStoreReady(context: Context, uri: Uri) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        }
    }
}

@Composable
fun CameraScreen(
    allPermissionsGranted: Boolean,
    shouldShowRationale: Boolean,
    onRequestPermission: () -> Unit
) {
    if (!allPermissionsGranted) {
        PermissionRequestScreen(
            onRequestPermission = onRequestPermission,
            shouldShowRationale = shouldShowRationale
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    CameraContent(
        context = context,
        lifecycleOwner = lifecycleOwner
    )
}

@Composable
private fun PermissionRequestScreen(
    onRequestPermission: () -> Unit,
    shouldShowRationale: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_flash_on),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = Color.White
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "需要相机权限",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (shouldShowRationale) {
                "拍照和保存照片需要相机和存储权限。请在设置中开启。"
            } else {
                "点击下方按钮授予相机权限，开始拍摄精彩瞬间。"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = onRequestPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "授予权限",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@SuppressLint("AutoboxingStateCreation")
@Composable
private fun CameraContent(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val previewView = remember {
        runCatching {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }.getOrNull() ?: PreviewView(context)
    }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var camera: Camera? by remember { mutableStateOf(null) }
    var isBackCamera by remember { mutableStateOf(true) }
    var torchEnabled by remember { mutableStateOf(false) }
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var lastPhotoBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureAnim by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var focusPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var showFocusRing by remember { mutableStateOf(false) }

    // 内置图片查看器状态
    var showViewer by remember { mutableStateOf(false) }
    var viewerBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var viewerScale by remember { mutableFloatStateOf(1f) }
    var viewerOffset by remember { mutableStateOf(Offset.Zero) }

    // 扫一扫相关
    var scanMode by remember { mutableStateOf(false) }
    var lastScanRaw by remember { mutableStateOf<String?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }
    var scanDialogText by remember { mutableStateOf<String?>(null) }
    var showOpenBrowserConfirm by remember { mutableStateOf(false) }

    val vibrateOnce: () -> Unit = remember {
        {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                } ?: return@runCatching
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(120L)
                }
            }
        }
    }

    val copyToClipboard: (String) -> Unit = remember {
        { text ->
            runCatching {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return@runCatching
                val clip = ClipData.newPlainText("scan_result", text)
                clipboard.setPrimaryClip(clip)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val barcodeScanner: BarcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    fun handleScanSuccess(raw: String) {
        if (raw == lastScanRaw) return
        lastScanRaw = raw
        vibrateOnce()
        scanDialogText = raw
        showScanDialog = true
    }

    fun scanLocalImage(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
            if (bmp == null) {
                mainHandler.post {
                    Toast.makeText(context, "无法读取图片", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val inputImage = InputImage.fromBitmap(bmp, 0)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val first = barcodes.firstOrNull()?.rawValue
                    if (!first.isNullOrEmpty()) {
                        mainHandler.post { handleScanSuccess(first) }
                    } else {
                        mainHandler.post {
                            Toast.makeText(context, "未识别到条码", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener {
                    mainHandler.post {
                        Toast.makeText(context, "识别失败", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) scanLocalImage(uri)
    }

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                runCatching { cameraProvider?.unbindAll() }
                runCatching {
                    cameraExecutor.shutdownNow()
                    runCatching { cameraExecutor.awaitTermination(1, TimeUnit.SECONDS) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { lifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { cameraProvider?.unbindAll() }
            runCatching {
                cameraExecutor.shutdownNow()
                runCatching { cameraExecutor.awaitTermination(1, TimeUnit.SECONDS) }
            }
        }
    }

    LaunchedEffect(isBackCamera, lifecycleOwner, retryKey, scanMode, barcodeScanner) {
        initError = null
        runCatching {
            val provider = withContext(Dispatchers.IO) {
                val future = ProcessCameraProvider.getInstance(context)
                future.get(10, TimeUnit.SECONDS)
            }
            cameraProvider = provider

            val selector = if (isBackCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            val preview = Preview.Builder().build()
            runCatching { preview.setSurfaceProvider(previewView.surfaceProvider) }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .build()
            imageCapture = capture

            val useCases = mutableListOf(preview, capture)

            val analysis = if (scanMode) {
                val a = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                a.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        barcodeScanner.process(inputImage)
                            .addOnSuccessListener { barcodes ->
                                if (barcodes.isNotEmpty()) {
                                    val first = barcodes.firstOrNull()
                                    val raw = first?.rawValue
                                    if (!raw.isNullOrEmpty()) {
                                        handleScanSuccess(raw)
                                    }
                                }
                            }
                            .addOnCompleteListener {
                                runCatching { imageProxy.close() }
                            }
                            .addOnFailureListener {
                                runCatching { imageProxy.close() }
                            }
                    } else {
                        runCatching { imageProxy.close() }
                    }
                }
                a
            } else null
            if (analysis != null) useCases += analysis

            val boundCamera = withContext(Dispatchers.Main.immediate) {
                runCatching { provider.unbindAll() }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    *useCases.toTypedArray()
                )
            }
            camera = boundCamera
            if (!isBackCamera) {
                torchEnabled = false
            }
            runCatching { boundCamera.cameraControl.enableTorch(torchEnabled) }
            // 相机就绪后：同步 zoom 状态，并确保回到 1.0x
            runCatching {
                val initZs = boundCamera.cameraInfo.zoomState.value
                if (initZs != null) zoomRatio = 1.0f
                boundCamera.cameraControl.setZoomRatio(1.0f)
            }
            // 实时同步 zoomRatio 到 UI（用户手势缩放后 camera 内部可能夹值）
            runCatching {
                boundCamera.cameraInfo.zoomState.observe(lifecycleOwner) { zs ->
                    zoomRatio = zs.zoomRatio
                }
            }
        }.onFailure { err ->
            err.printStackTrace()
            initError = err.message ?: "未知错误"
            mainHandler.post {
                Toast.makeText(context, "相机初始化失败: ${initError}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(previewView, camera, density) {
        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val info = cam.cameraInfo
                    val zoomState = info.zoomState.value ?: return false
                    val current = zoomState.zoomRatio
                    val next = (current * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    runCatching { cam.cameraControl.setZoomRatio(next) }
                    return true
                }
            }
        )
        val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val cam = camera ?: return false
                    val factory = previewView.meteringPointFactory
                    val point = runCatching {
                        factory.createPoint(e.x, e.y)
                    }.getOrNull() ?: return false
                    val focusAction = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
                    ).apply {
                        setAutoCancelDuration(3, TimeUnit.SECONDS)
                    }.build()
                    runCatching { cam.cameraControl.startFocusAndMetering(focusAction) }
                    focusPoint = (e.x to e.y)
                    showFocusRing = true
                    scope.launch {
                        delay(1200.milliseconds)
                        showFocusRing = false
                    }
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean = true
            }
        )
        val touchListener = android.view.View.OnTouchListener { _, event ->
            runCatching {
                scaleDetector.onTouchEvent(event)
                tapDetector.onTouchEvent(event)
            }.getOrDefault(false)
            true
        }
        previewView.setOnTouchListener(touchListener)
        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    fun takePhoto() {
        val capture = imageCapture ?: run {
            Toast.makeText(context, "相机尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "IMG_${DateFormat.format("yyyyMMdd_HHmmss", Date())}.png"
        val mediaUri = createPhotoMediaStoreUri(context, fileName) ?: run {
            Toast.makeText(context, "无法创建保存路径 (已尝试 Photo/Pictures/Photo/DCIM/Photo/DCIM)", Toast.LENGTH_LONG).show()
            return
        }

        captureAnim = true

        runCatching {
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    @SuppressLint("UseKtx")
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        val (jpegBytes, rotationDegrees) = runCatching {
                            val buffer = imageProxy.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            bytes to imageProxy.imageInfo.rotationDegrees
                        }.getOrElse {
                            runCatching { imageProxy.close() }
                            mainHandler.post {
                                captureAnim = false
                                runCatching { context.contentResolver.delete(mediaUri, null, null) }
                                Toast.makeText(context, "读取帧失败", Toast.LENGTH_SHORT).show()
                            }
                            return
                        }
                        runCatching { imageProxy.close() }

                        // 1. 立即给 UI 反馈：关掉闪屏，用户可以继续操作（PNG 后台保存）
                        mainHandler.post {
                            captureAnim = false
                            Toast.makeText(context, "拍照成功，保存中…", Toast.LENGTH_SHORT).show()
                        }

                        // 2. 后台做 完整Bitmap decode(+rotate) + 缩略图 inSampleSize decode(+rotate)
                        var fullBmp: Bitmap? = null
                        var thumbBmp: Bitmap? = null
                        runCatching {
                            val fullOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                            fullBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, fullOpts)
                            val fb = fullBmp
                            if (rotationDegrees != 0 && fb != null) {
                                val m = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                val rotated = Bitmap.createBitmap(fb, 0, 0, fb.width, fb.height, m, true)
                                if (rotated !== fb) fb.recycle()
                                fullBmp = rotated
                            }

                            val thumbOpts = BitmapFactory.Options().apply {
                                inSampleSize = 8
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            thumbBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, thumbOpts)
                            val tb = thumbBmp
                            if (rotationDegrees != 0 && tb != null) {
                                val m = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                val rotated = Bitmap.createBitmap(tb, 0, 0, tb.width, tb.height, m, true)
                                if (rotated !== tb) tb.recycle()
                                thumbBmp = rotated
                            }
                        }.getOrElse {
                            mainHandler.post {
                                runCatching { context.contentResolver.delete(mediaUri, null, null) }
                                Toast.makeText(context, "图像解码失败", Toast.LENGTH_SHORT).show()
                            }
                            fullBmp?.recycle()
                            thumbBmp?.recycle()
                            return
                        }

                        val bitmap = fullBmp
                        if (bitmap == null) {
                            mainHandler.post {
                                runCatching { context.contentResolver.delete(mediaUri, null, null) }
                                Toast.makeText(context, "图像解码失败", Toast.LENGTH_SHORT).show()
                            }
                            thumbBmp?.recycle()
                            return
                        }

                        // 3. PNG 存盘
                        runCatching {
                            context.contentResolver.openOutputStream(mediaUri, "w").use { out ->
                                checkNotNull(out) { "无法打开输出流" }
                                bitmap.compress(Bitmap.CompressFormat.PNG, 0, out)
                            }
                        }.getOrElse { err ->
                            mainHandler.post {
                                runCatching { context.contentResolver.delete(mediaUri, null, null) }
                                Toast.makeText(
                                    context,
                                    "保存 PNG 失败: ${err.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            bitmap.recycle()
                            thumbBmp?.recycle()
                            return
                        }

                        markMediaStoreReady(context, mediaUri)

                        // 4. 更新缩略图/路径 + 最终完成提示
                        mainHandler.post {
                            lastPhotoUri = mediaUri
                            val thumb = thumbBmp
                            if (thumb != null) {
                                val prev = lastPhotoBitmap
                                lastPhotoBitmap = thumb
                                prev?.recycle()
                            } else {
                                lastPhotoBitmap = bitmap
                            }
                            if (thumb !== bitmap) bitmap.recycle()
                            Toast.makeText(context, "已保存 PNG: $fileName", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        mainHandler.post {
                            captureAnim = false
                            runCatching { context.contentResolver.delete(mediaUri, null, null) }
                            Toast.makeText(
                                context,
                                "拍照失败: ${exception.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }.onFailure {
            captureAnim = false
            runCatching { context.contentResolver.delete(mediaUri, null, null) }
            Toast.makeText(context, "拍照异常: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 变焦倍数显示
        if (zoomRatio > 1.001f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = String.format("%.1fx", zoomRatio),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // 点击对焦框
        val fp = focusPoint
        if (fp != null) {
            val px = with(density) { fp.first.toDp() }
            val py = with(density) { fp.second.toDp() }
            AnimatedVisibility(
                visible = showFocusRing,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(220)),
                modifier = Modifier.offset {
                    IntOffset(
                        (px - 45.dp).roundToPx(),
                        (py - 45.dp).roundToPx()
                    )
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .border(
                            width = 2.5.dp,
                            color = Color(0xFFE8C547),
                            shape = RoundedCornerShape(8.dp)
                        )
                )
            }
        }

        AnimatedVisibility(
            visible = captureAnim,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.5f))
            )
        }

        if (scanMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(260.dp)
                ) {
                    val cornerColor = Color(0xFF4ECDC4)
                    val cornerWidth = 4.dp
                    val cornerLen = 36.dp
                    // 左上
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(cornerLen, cornerWidth)
                            .background(cornerColor)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(cornerWidth, cornerLen)
                            .background(cornerColor)
                    )
                    // 右上
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(cornerLen, cornerWidth)
                            .background(cornerColor)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(cornerWidth, cornerLen)
                            .background(cornerColor)
                    )
                    // 左下
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .size(cornerLen, cornerWidth)
                            .background(cornerColor)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .size(cornerWidth, cornerLen)
                            .background(cornerColor)
                    )
                    // 右下
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(cornerLen, cornerWidth)
                            .background(cornerColor)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(cornerWidth, cornerLen)
                            .background(cornerColor)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 180.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "将条码放入框内",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (initError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "相机启动失败",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = initError!!,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(32.dp))
                Button(onClick = { retryKey++ }) {
                    Text(text = "重试")
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        torchEnabled = !torchEnabled
                        runCatching { camera?.cameraControl?.enableTorch(torchEnabled) }
                            .onFailure {
                                Toast.makeText(context, "闪光灯切换失败", Toast.LENGTH_SHORT).show()
                            }
                    },
                    enabled = isBackCamera
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (torchEnabled && isBackCamera) R.drawable.ic_flash_on
                            else R.drawable.ic_flash_off
                        ),
                        contentDescription = "闪光灯",
                        modifier = Modifier.size(32.dp),
                        tint = if (!isBackCamera) Color.Gray else Color.Unspecified
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            if (scanMode) {
                                scanMode = false
                                lastScanRaw = null
                                showScanDialog = false
                            }
                        }
                    ) {
                        Text(
                            text = "拍照",
                            color = if (!scanMode) Color.White else Color.White.copy(alpha = 0.55f),
                            style = if (!scanMode) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyMedium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 18.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    )
                    TextButton(
                        onClick = {
                            if (!scanMode) {
                                scanMode = true
                                lastScanRaw = null
                                showScanDialog = false
                            }
                        }
                    ) {
                        Text(
                            text = "扫码",
                            color = if (scanMode) Color.White else Color.White.copy(alpha = 0.55f),
                            style = if (scanMode) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                IconButton(onClick = { isBackCamera = !isBackCamera }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_switch_camera),
                        contentDescription = "切换摄像头",
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(horizontal = 32.dp, vertical = 48.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Card(
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.DarkGray.copy(alpha = 0.5f)
                    ),
                    onClick = {
                        val uri = lastPhotoUri
                        if (uri != null) {
                            viewerBitmap?.recycle()
                            viewerBitmap = null
                            viewerScale = 1f
                            viewerOffset = Offset.Zero
                            // IO 线程解码大图（无 inSampleSize，完整分辨率）
                            scope.launch(Dispatchers.IO) {
                                val fullBmp = runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        BitmapFactory.decodeStream(stream)
                                    }
                                }.getOrNull()
                                if (fullBmp == null) {
                                    mainHandler.post {
                                        Toast.makeText(context, "无法加载原图", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    mainHandler.post {
                                        viewerBitmap = fullBmp
                                        showViewer = true
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(context, "还没有拍摄照片", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = lastPhotoBitmap
                        if (bmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "最近照片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_photo),
                                contentDescription = "相册",
                                modifier = Modifier.size(32.dp),
                                tint = Color.Gray
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .padding(6.dp)
                        .clickable { takePhoto() }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                if (scanMode) {
                    Card(
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.DarkGray.copy(alpha = 0.5f)
                        ),
                        onClick = {
                            runCatching { pickImageLauncher.launch("image/*") }
                                .onFailure {
                                    Toast.makeText(context, "无法打开相册", Toast.LENGTH_SHORT).show()
                                }
                        }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_photo),
                                    contentDescription = "选图扫码",
                                    modifier = Modifier.size(28.dp),
                                    tint = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "选图",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.size(64.dp))
                }
            }
        }

        // 内置图片查看器（全屏叠层）
        if (showViewer) {
            BackHandler {
                showViewer = false
                viewerScale = 1f
                viewerOffset = Offset.Zero
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // 顶部栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = {
                            showViewer = false
                            viewerScale = 1f
                            viewerOffset = Offset.Zero
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_flash_off),
                            contentDescription = "关闭",
                            modifier = Modifier.size(28.dp),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "照片查看器  ${String.format("%.1fx", viewerScale)}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.size(48.dp))
                }

                // 图片（支持缩放 + 平移 + 双击）
                val vbmp = viewerBitmap
                if (vbmp != null) {
                    Image(
                        bitmap = vbmp.asImageBitmap(),
                        contentDescription = "查看照片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = viewerScale,
                                scaleY = viewerScale,
                                translationX = if (viewerScale > 1.001f) viewerOffset.x else 0f,
                                translationY = if (viewerScale > 1.001f) viewerOffset.y else 0f
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures(
                                    onGesture = { _, pan, zoom, _ ->
                                        val newScale = (viewerScale * zoom)
                                            .coerceIn(1f, 5f)
                                        if (newScale == 1f) {
                                            viewerScale = 1f
                                            viewerOffset = Offset.Zero
                                        } else {
                                            viewerScale = newScale
                                            viewerOffset += pan
                                        }
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { tapOffset ->
                                        if (viewerScale > 1.001f) {
                                            viewerScale = 1f
                                            viewerOffset = Offset.Zero
                                        } else {
                                            viewerScale = 3f
                                            viewerOffset = Offset(
                                                x = -tapOffset.x * 2f,
                                                y = -tapOffset.y * 2f
                                            )
                                        }
                                    },
                                    onTap = { }
                                )
                            }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "加载中...",
                            color = Color.Gray,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        if (showScanDialog && scanDialogText != null) {
            val text = scanDialogText!!
            val isUrl = text.startsWith("http://") || text.startsWith("https://")
            AlertDialog(
                onDismissRequest = {
                    showScanDialog = false
                    lastScanRaw = null
                },
                title = { Text(text = "扫描结果") },
                text = {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                copyToClipboard(text)
                                showScanDialog = false
                                lastScanRaw = null
                            }
                        ) {
                            Text("复制")
                        }
                        if (isUrl) {
                            TextButton(
                                onClick = {
                                    showOpenBrowserConfirm = true
                                }
                            ) {
                                Text("打开链接")
                            }
                        }
                        TextButton(
                            onClick = {
                                showScanDialog = false
                                lastScanRaw = null
                            }
                        ) {
                            Text("关闭")
                        }
                    }
                }
            )
        }

        if (showOpenBrowserConfirm && scanDialogText != null) {
            val text = scanDialogText!!
            AlertDialog(
                onDismissRequest = {
                    showOpenBrowserConfirm = false
                },
                title = { Text(text = "打开浏览器") },
                text = {
                    Column {
                        Text(
                            text = "是否要在浏览器中打开以下链接？",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    Uri.parse(text)
                                ).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                            showOpenBrowserConfirm = false
                            showScanDialog = false
                            lastScanRaw = null
                        }
                    ) {
                        Text("打开")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showOpenBrowserConfirm = false
                        }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}
