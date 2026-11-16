package com.trackr.core.network.remote.lenz

/** iOS 侧暂未实现朗斯 TCP 通道；返回 null 即视为该数据源不可用 */
internal actual suspend fun lenzSocketSendAndReceive(
    host: String,
    port: Int,
    reqData: ByteArray,
    timeoutMs: Int
): ByteArray? = null

internal actual fun lenzDecodeGbk(bytes: ByteArray): String = bytes.decodeToString()

internal actual fun lenzEncodeGbk(text: String): ByteArray = text.encodeToByteArray()

internal actual suspend fun lenzHttpGetText(url: String, timeoutMs: Int): String? = null

internal actual suspend fun lenzSocketProbe(host: String, port: Int, timeoutMs: Int): Boolean = false
