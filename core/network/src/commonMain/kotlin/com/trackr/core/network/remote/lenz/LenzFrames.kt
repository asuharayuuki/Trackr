package com.trackr.core.network.remote.lenz

/**
 * 朗斯「掌上出行」应答帧解析。
 *
 * 帧结构（大端）：
 * ```
 * 02 | s3(2B) | cmd(1B) | 序号(2B) | [分片数(2B) 分片序号(2B)] | 载荷(N B) | 00 FE 03
 * ```
 * - `s3 & 0x3FF` = 本帧载荷长度 N；`s3` 的第 10 位为 1 表示这一条应答被切成了多个分片。
 * - 一条长应答（如中文关键词搜出几十条线路）会被切成多帧，各帧载荷按分片序拼起来才是完整 JSON。
 * - 每帧载荷是 GBK 编码的 JSON 文本。
 *
 * 只解析单帧会把长应答截断，正是「搜中文搜不到」的原因。
 */
internal object LenzFrames {
    private const val STX = 0x02
    private const val TAIL_LEN = 3
    private const val HEADER_LEN = 6
    private const val MULTI_HEADER_EXTRA = 4

    /**
     * 已收到的数据是否已经是一条完整应答。
     *
     * 长应答会分片下发，最后一帧的 `分片序号 == 分片总数`；见到它就说明可以停了，
     * 不必再等空闲超时，能让每次查询少等 1 秒。
     */
    fun isResponseComplete(raw: ByteArray): Boolean {
        var i = 0
        while (i < raw.size) {
            if ((raw[i].toInt() and 0xFF) != STX) {
                i++
                continue
            }
            if (i + HEADER_LEN > raw.size) return false
            val s3 = ((raw[i + 1].toInt() and 0xFF) shl 8) or (raw[i + 2].toInt() and 0xFF)
            val payloadLen = s3 and 0x3FF
            val multi = ((s3 shr 10) and 1) == 1
            var p = i + HEADER_LEN
            var sliceCount = 1
            var sliceIndex = 1
            if (multi) {
                if (p + MULTI_HEADER_EXTRA > raw.size) return false
                sliceCount = ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
                sliceIndex = ((raw[p + 2].toInt() and 0xFF) shl 8) or (raw[p + 3].toInt() and 0xFF)
                p += MULTI_HEADER_EXTRA
            }
            val end = p + payloadLen
            if (end > raw.size) return false
            if (sliceCount == 1 || sliceIndex >= sliceCount) return true
            i = end + TAIL_LEN
        }
        return false
    }

    /** 把原始字节流拆成若干完整 JSON 文本（按命令分组、按分片序拼接） */
    fun extractJsonDocuments(raw: ByteArray): List<String> {
        if (raw.isEmpty()) return emptyList()

        val byCommand = LinkedHashMap<Int, MutableList<Pair<Int, ByteArray>>>()
        var i = 0
        while (i < raw.size) {
            if ((raw[i].toInt() and 0xFF) != STX) {
                i++
                continue
            }
            if (i + HEADER_LEN > raw.size) break

            val s3 = ((raw[i + 1].toInt() and 0xFF) shl 8) or (raw[i + 2].toInt() and 0xFF)
            val payloadLen = s3 and 0x3FF
            val multi = ((s3 shr 10) and 1) == 1
            val command = raw[i + 3].toInt() and 0xFF

            var p = i + HEADER_LEN
            var sliceIndex = 1
            if (multi) {
                if (p + MULTI_HEADER_EXTRA > raw.size) break
                // 前两字节是分片总数，这里只需要分片序号
                sliceIndex = ((raw[p + 2].toInt() and 0xFF) shl 8) or (raw[p + 3].toInt() and 0xFF)
                p += MULTI_HEADER_EXTRA
            }
            val end = p + payloadLen
            if (end > raw.size) break

            byCommand.getOrPut(command) { mutableListOf() }.add(sliceIndex to raw.copyOfRange(p, end))
            i = end + TAIL_LEN
        }

        return byCommand.values.map { slices ->
            // 分片按序号拼回完整字节，再整体按 GBK 解码
            val bytes = slices.sortedBy { it.first }.map { it.second }
            lenzDecodeGbk(bytes.reduce { acc, part -> acc + part })
                .trim()
                .trim('\u0000')
        }
    }
}
