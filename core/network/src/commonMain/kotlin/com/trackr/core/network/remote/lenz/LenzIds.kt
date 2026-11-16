package com.trackr.core.network.remote.lenz

import com.trackr.core.model.TransitCity

/**
 * 朗斯掌上出行 `lz:` 协议 id 的统一编解码入口。
 *
 * - 线路：`lz:{serverIp}:{serverPort}:{routeId}:{upDown}:{lineName}`
 * - 站点：`lz:{serverIp}:{serverPort}:{stationId}:{stationName}`
 */
object LenzIds {
    const val PREFIX = "lz:"

    /** 是否是朗斯掌上出行协议的 id（线路、站点共用同一个前缀） */
    fun isLenzId(id: String?): Boolean = id?.startsWith(PREFIX) == true

    /** 解析后的线路坐标 */
    data class LineRef(
        val serverIp: String,
        val serverPort: Int,
        val routeId: String,
        val upDown: Int,
        val lineName: String
    ) {
        val isReverse: Boolean
            get() = upDown == 1

        /** 反向方向：0 <-> 1 */
        fun reversed(): LineRef = copy(upDown = if (upDown == 0) 1 else 0)

        fun lineId(): String = lineId(serverIp, serverPort, routeId, upDown, lineName)
    }

    fun lineId(serverIp: String, serverPort: Int, routeId: String, upDown: Int, lineName: String): String =
        "$PREFIX$serverIp:$serverPort:$routeId:$upDown:$lineName"

    fun parseLineId(lineId: String): LineRef? {
        if (!isLenzId(lineId)) return null
        val parts = lineId.removePrefix(PREFIX).split(":")
        if (parts.size < 5) return null
        val serverIp = parts[0]
        val serverPort = parts[1].toIntOrNull() ?: return null
        val routeId = parts[2]
        val upDown = parts[3].toIntOrNull() ?: 0
        val lineName = parts.subList(4, parts.size).joinToString(":")
        return LineRef(serverIp, serverPort, routeId, upDown, lineName)
    }

    fun stopId(serverIp: String, serverPort: Int, stationId: String, stationName: String): String =
        "$PREFIX$serverIp:$serverPort:$stationId:$stationName"

    fun parseStopId(stopId: String): Pair<String, String>? {
        if (!isLenzId(stopId)) return null
        val parts = stopId.removePrefix(PREFIX).split(":")
        if (parts.size < 4) return null
        val stationId = parts[2]
        val stationName = parts.subList(3, parts.size).joinToString(":")
        return stationId to stationName
    }

    fun cleanStationName(raw: String): String = raw
        .removeSuffix(" (掌上出行)")
        .removeSuffix("(掌上出行)")
        .removeSuffix("（掌上出行）")
        .removeSuffix(" [掌上出行]")
        .removeSuffix("[掌上出行]")
        .trim()
}
