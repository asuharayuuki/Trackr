package com.trackr.core.network.remote.lenz

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 收到最后一帧后，再等这么久没有新数据就认为应答结束 */
private const val IDLE_TIMEOUT_MS = 800

/** 单次应答最多收这么多字节，防止异常服务器把内存吃满 */
private const val MAX_RESPONSE_BYTES = 512 * 1024

internal actual suspend fun lenzSocketSendAndReceive(
    host: String,
    port: Int,
    reqData: ByteArray,
    timeoutMs: Int
): ByteArray? = withContext(Dispatchers.IO) {
    var socket: Socket? = null
    try {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = IDLE_TIMEOUT_MS
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        out.write(reqData)
        out.flush()

        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (baos.size() < MAX_RESPONSE_BYTES) {
            val read = try {
                inp.read(buffer)
            } catch (_: SocketTimeoutException) {
                // 长应答分片下发；万一没等到收尾帧，读空闲超时也按结束处理
                break
            }
            if (read <= 0) break
            baos.write(buffer, 0, read)
            // 见到收尾分片就不再等服务端关连接，省一次 1 秒的空闲等待
            if (LenzFrames.isResponseComplete(baos.toByteArray())) break
        }
        val result = baos.toByteArray()
        if (result.isNotEmpty()) result else null
    } catch (_: Exception) {
        null
    } finally {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}

internal actual fun lenzDecodeGbk(bytes: ByteArray): String =
    String(bytes, Charset.forName("GBK"))

internal actual fun lenzEncodeGbk(text: String): ByteArray =
    text.toByteArray(Charset.forName("GBK"))

/** 原始 socket 发明文 HTTP/1.0 GET，绕开 Android 的明文 HTTP 拦截 */
internal actual suspend fun lenzHttpGetText(url: String, timeoutMs: Int): String? =
    withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return@withContext null
            val path = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val port = if (uri.port > 0) uri.port else 80

            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.0\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: Trackr/1.0\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }

            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = socket.getInputStream()
            while (baos.size() < MAX_RESPONSE_BYTES) {
                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (read <= 0) break
                baos.write(buffer, 0, read)
            }
            val raw = baos.toByteArray()
            if (raw.isEmpty()) return@withContext null

            // 去掉响应头：找第一个空行
            val headerEnd = indexOfHeaderEnd(raw)
            if (headerEnd < 0) return@withContext null
            val statusLine = String(raw, 0, minOf(32, raw.size), Charsets.US_ASCII)
            if (!statusLine.contains(" 200")) return@withContext null
            String(raw, headerEnd, raw.size - headerEnd, Charset.forName("UTF-8"))
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

internal actual suspend fun lenzSocketProbe(host: String, port: Int, timeoutMs: Int): Boolean =
    withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

private fun indexOfHeaderEnd(bytes: ByteArray): Int {
    for (i in 0..bytes.size - 4) {
        if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
            bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
        ) {
            return i + 4
        }
    }
    return -1
}
