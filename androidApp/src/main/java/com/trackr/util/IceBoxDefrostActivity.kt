package com.trackr.util

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.catchingnow.icebox.sdk_client.IceBox
import com.trackr.core.model.TransitCodeProvider

/**
 * 申请冰箱权限并自动解冻的透明 Activity。
 * 当需要向用户弹出「请求调用冰箱 SDK 权限」系统弹窗时启动，授权完成后自动解冻并打开乘车码。
 */
class IceBoxDefrostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
    }

    private var providerId: String? = null
    private var targetPackage: String? = null
    private var appName: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            defrostAndLaunch()
        } else {
            Toast.makeText(this, "未授予冰箱解冻权限，无法自动解冻乘车码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerId = intent.getStringExtra(EXTRA_PROVIDER)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "应用"

        val pkg = targetPackage
        val pId = providerId
        if (pkg.isNullOrEmpty() || pId == null) {
            finish()
            return
        }

        if (ContextCompat.checkSelfPermission(this, IceBox.SDK_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            defrostAndLaunch()
        } else {
            permissionLauncher.launch(IceBox.SDK_PERMISSION)
        }
    }

    private fun defrostAndLaunch() {
        val provider = TransitCodeProvider.fromId(providerId)
        val pkg = targetPackage ?: return
        val name = appName ?: "应用"

        TransitCodeHelper.unfreezeAndLaunch(this, provider, pkg, name) {
            finish()
        }
    }
}
