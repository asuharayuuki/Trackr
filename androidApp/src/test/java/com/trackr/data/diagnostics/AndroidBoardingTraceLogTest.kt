package com.trackr.data.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 判上车判定记录的 Android 实现（issue #49）：写文件、写满换一份、导出交给分享面板 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBoardingTraceLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "diagnostics")
    private val shared = mutableListOf<Intent>()

    @Before
    fun setUp() {
        dir.deleteRecursively()
    }

    private fun TestScope.log(maxBytes: Long = 1_000_000L) = AndroidBoardingTraceLog(
        context, scope = this, io = StandardTestDispatcher(testScheduler), maxBytes = maxBytes, launchShare = { shared += it }
    )

    @Test
    fun record_appendsOneLineEach() = runTest {
        val log = log()
        log.record("""{"t":1}""")
        log.record("""{"t":2}""")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("""{"t":1}""", """{"t":2}"""), File(dir, "boarding_trace.jsonl").readLines())
    }

    @Test
    fun record_rotatesWhenFull_keepingOnePreviousFile() = runTest {
        val log = log(maxBytes = 20)
        listOf("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc").forEach { log.record(it) }
        testScheduler.advanceUntilIdle()
        // 每写一行都满了：最新一行在当前文件，上一行在 .1，更早的丢掉
        assertEquals(listOf("cccccccccccc"), File(dir, "boarding_trace.jsonl").readLines())
        assertEquals(listOf("bbbbbbbbbbbb"), File(dir, "boarding_trace.jsonl.1").readLines())
    }

    @Test
    fun share_withoutRecords_returnsFalse() = runTest {
        assertFalse(log().share())
        assertTrue(shared.isEmpty())
    }

    @Test
    fun share_joinsOldAndCurrent_andOpensChooser() = runTest {
        val log = log(maxBytes = 20)
        listOf("bbbbbbbbbbbb", "cccccccccccc").forEach { log.record(it) }
        testScheduler.advanceUntilIdle()

        assertTrue(log.share())
        // 旧的在前、新的在后
        assertEquals(listOf("bbbbbbbbbbbb", "cccccccccccc"), File(dir, "trackr_boarding_trace.txt").readLines())
        val chooser = shared.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        // 经 FileProvider 给出去：update_paths.xml 里要有 diagnostics/ 这一条
        assertEquals("content", uri?.scheme)
        assertTrue(uri.toString().endsWith("/diagnostics/trackr_boarding_trace.txt"))
    }
}
