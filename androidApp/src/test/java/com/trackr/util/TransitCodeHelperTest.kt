package com.trackr.util

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.catchingnow.icebox.sdk_client.IceBox
import com.trackr.core.model.TransitCodeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TransitCodeHelperTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
    }

    @Test
    fun testOpenAlipayTransitCode_createsCorrectIntent() {
        val result = TransitCodeHelper.openAlipayTransitCode(app)
        assertTrue(result)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_ALIPAY_TRANSIT), startedIntent.data)
        assertTrue((startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testOpenUnionPayTransitCode_createsCorrectIntent() {
        val result = TransitCodeHelper.openUnionPayTransitCode(app)
        assertTrue(result)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_UNIONPAY_TRANSIT), startedIntent.data)
        assertTrue((startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testOpenTransitCode_dispatchesProperly() {
        // 1. Alipay
        TransitCodeHelper.openTransitCode(app, TransitCodeProvider.ALIPAY)
        val alipayIntent = shadowOf(app).nextStartedActivity
        assertNotNull(alipayIntent)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_ALIPAY_TRANSIT), alipayIntent.data)

        // 2. UnionPay
        TransitCodeHelper.openTransitCode(app, TransitCodeProvider.UNIONPAY)
        val unionpayIntent = shadowOf(app).nextStartedActivity
        assertNotNull(unionpayIntent)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_UNIONPAY_TRANSIT), unionpayIntent.data)

        // 3. ASK_ALWAYS returns false without launching
        val askAlwaysResult = TransitCodeHelper.openTransitCode(app, TransitCodeProvider.ASK_ALWAYS)
        assertFalse(askAlwaysResult)
    }

    @Test
    fun testTransitCodeProvider_fromId() {
        assertEquals(TransitCodeProvider.ALIPAY, TransitCodeProvider.fromId("alipay"))
        assertEquals(TransitCodeProvider.UNIONPAY, TransitCodeProvider.fromId("unionpay"))
        assertEquals(TransitCodeProvider.ASK_ALWAYS, TransitCodeProvider.fromId("ask_always"))
        assertEquals(TransitCodeProvider.ASK_ALWAYS, TransitCodeProvider.fromId("unknown"))
        assertEquals(TransitCodeProvider.ASK_ALWAYS, TransitCodeProvider.fromId(null))
    }

    @Test
    fun testIsAppFrozen_detectsDisabledPackage() {
        val shadowPm = shadowOf(app.packageManager)
        val packageInfo = PackageInfo().apply {
            packageName = TransitCodeHelper.PACKAGE_ALIPAY
            applicationInfo = ApplicationInfo().apply {
                packageName = TransitCodeHelper.PACKAGE_ALIPAY
                enabled = false
            }
        }
        shadowPm.installPackage(packageInfo)

        assertTrue(TransitCodeHelper.isAppInstalled(app, TransitCodeHelper.PACKAGE_ALIPAY))
        assertTrue(TransitCodeHelper.isAppFrozen(app, TransitCodeHelper.PACKAGE_ALIPAY))
    }

    @Test
    fun testOpenTransitCode_whenFrozenWithoutIcebox_returnsFalse() {
        val shadowPm = shadowOf(app.packageManager)
        val packageInfo = PackageInfo().apply {
            packageName = TransitCodeHelper.PACKAGE_ALIPAY
            applicationInfo = ApplicationInfo().apply {
                packageName = TransitCodeHelper.PACKAGE_ALIPAY
                enabled = false
            }
        }
        shadowPm.installPackage(packageInfo)

        val result = TransitCodeHelper.openTransitCode(app, TransitCodeProvider.ALIPAY)
        assertFalse(result)
    }

    @Test
    fun testOpenTransitCode_whenFrozenWithIcebox_launchesDefrostActivityWhenNoPermission() {
        val shadowPm = shadowOf(app.packageManager)
        // 安装支付宝（被冻结）
        val alipayInfo = PackageInfo().apply {
            packageName = TransitCodeHelper.PACKAGE_ALIPAY
            applicationInfo = ApplicationInfo().apply {
                packageName = TransitCodeHelper.PACKAGE_ALIPAY
                enabled = false
            }
        }
        shadowPm.installPackage(alipayInfo)

        // 安装冰箱
        val iceboxInfo = PackageInfo().apply {
            packageName = IceBox.PACKAGE_NAME
            applicationInfo = ApplicationInfo().apply {
                packageName = IceBox.PACKAGE_NAME
                enabled = true
            }
        }
        shadowPm.installPackage(iceboxInfo)

        // 调用 openTransitCode
        val result = TransitCodeHelper.openTransitCode(app, TransitCodeProvider.ALIPAY)
        assertTrue(result)

        // 验证拉起 IceBoxDefrostActivity 申请权限
        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(IceBoxDefrostActivity::class.java.name, startedIntent.component?.className)
        assertEquals(TransitCodeProvider.ALIPAY.id, startedIntent.getStringExtra(IceBoxDefrostActivity.EXTRA_PROVIDER))
        assertEquals(TransitCodeHelper.PACKAGE_ALIPAY, startedIntent.getStringExtra(IceBoxDefrostActivity.EXTRA_PACKAGE_NAME))
    }
}
