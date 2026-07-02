package com.zztx.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private var allPermissionsGranted by mutableStateOf(false)
    private var shouldShowRationale by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        runCatching {
            allPermissionsGranted = hasAllRequiredPermissions(permissions)
            shouldShowRationale = requiredPermissions().any {
                runCatching { shouldShowRequestPermissionRationale(it) }.getOrDefault(false)
            }
        }.onFailure {
            Toast.makeText(this, "权限回调异常: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            else -> {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun hasAllRequiredPermissions(permissions: Map<String, Boolean>? = null): Boolean {
        return runCatching {
            val cameraGranted = if (permissions != null) {
                permissions[Manifest.permission.CAMERA] == true
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
            if (!cameraGranted) return false

            val mediaGranted: Boolean = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    val full = if (permissions != null) {
                        permissions[Manifest.permission.READ_MEDIA_IMAGES] == true
                    } else {
                        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    val partial = if (permissions != null) {
                        permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
                    } else {
                        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    // 完全访问 或 部分照片访问 (Android 14+ Selected Photos Access) 都视为通过
                    full || partial
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    if (permissions != null) {
                        permissions[Manifest.permission.READ_MEDIA_IMAGES] == true
                    } else {
                        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                }
                else -> {
                    if (permissions != null) {
                        permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                    } else {
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                }
            }
            mediaGranted
        }.getOrDefault(false)
    }

    private fun checkPermissions(): Boolean {
        return runCatching {
            val granted = hasAllRequiredPermissions()
            if (!granted) {
                shouldShowRationale = requiredPermissions().any {
                    runCatching { shouldShowRequestPermissionRationale(it) }.getOrDefault(false)
                }
            }
            granted
        }.getOrDefault(false)
    }

    fun requestPermissions() {
        runCatching {
            permissionLauncher.launch(requiredPermissions().toTypedArray())
        }.onFailure {
            Toast.makeText(this, "无法请求权限: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching {
            super.onCreate(savedInstanceState)
            allPermissionsGranted = checkPermissions()
            enableImmersiveMode()
            setContent {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        CameraScreen(
                            allPermissionsGranted = allPermissionsGranted,
                            shouldShowRationale = shouldShowRationale,
                            onRequestPermission = ::requestPermissions
                        )
                    }
                }
            }
        }.onFailure {
            it.printStackTrace()
            Toast.makeText(this, "启动失败: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        allPermissionsGranted = checkPermissions()
        runCatching { enableImmersiveMode() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            runCatching { enableImmersiveMode() }
        }
    }

    private fun enableImmersiveMode() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            runCatching {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(
                    WindowInsetsCompat.Type.statusBars()
                        or WindowInsetsCompat.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
            }
        }
    }
}
