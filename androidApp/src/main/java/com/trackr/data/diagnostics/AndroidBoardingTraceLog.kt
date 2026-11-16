package com.trackr.data.diagnostics

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.trackr.core.domain.platform.BoardingTraceLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 判上车判定记录的 Android 实现（issue #49）：一行一条 JSON，写在 `cacheDir/diagnostics/` 下。
 *
 * - 放缓存目录：不参与备份（记录里有追踪时的位置），系统清缓存时丢了也无妨；
 * - 写满 [maxBytes] 就把当前文件改名成 `.1`（覆盖更早的那份），所以最多占两份的大小；
 * - 写文件排在一条单线程的 IO 队列上，轮询那边不等它；
 * - 导出时把旧的和当前的拼成一个文件，经 FileProvider（`update_paths.xml` 里的 `diagnostics/`）交给系统分享面板。
 */
class AndroidBoardingTraceLog(
    context: Context,
    private val scope: CoroutineScope,
    @Suppress("OPT_IN_USAGE")
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val maxBytes: Long = MAX_BYTES,
    private val launchShare: (Intent) -> Unit = { context.applicationContext.startActivity(it) }
) : BoardingTraceLog {

    private val appContext: Context = context.applicationContext ?: context
    private val dir: File get() = File(appContext.cacheDir, DIR)
    private val current: File get() = File(dir, FILE)
    private val previous: File get() = File(dir, "$FILE.1")

    override fun record(line: String) {
        scope.launch(io) {
            try {
                dir.mkdirs()
                if (current.length() + line.length > maxBytes) {
                    previous.delete()
                    current.renameTo(previous)
                }
                current.appendText(line + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot write boarding trace", e)
            }
        }
    }

    override suspend fun share(): Boolean {
        val export = withContext(io) {
            try {
                val parts = listOf(previous, current).filter { it.length() > 0L }
                if (parts.isEmpty()) return@withContext null
                File(dir, EXPORT_FILE).also { out ->
                    out.outputStream().use { stream ->
                        parts.forEach { part -> part.inputStream().use { it.copyTo(stream) } }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot export boarding trace", e)
                null
            }
        } ?: return false
        return try {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", export)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchShare(
                Intent.createChooser(send, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot share boarding trace", e)
            false
        }
    }

    private companion object {
        const val TAG = "BoardingTraceLog"
        const val DIR = "diagnostics"
        const val FILE = "boarding_trace.jsonl"
        const val EXPORT_FILE = "trackr_boarding_trace.txt"

        /** 一份 1 MB：一行大约 1 KB，5 秒一轮也够记一个多小时的判定段 */
        const val MAX_BYTES = 1_000_000L
    }
}
