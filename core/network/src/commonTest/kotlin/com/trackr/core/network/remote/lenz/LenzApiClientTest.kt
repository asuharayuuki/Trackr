package com.trackr.core.network.remote.lenz

import com.trackr.core.model.TransitCity
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.testing.unusedHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LenzApiClientTest {

    @Test
    fun testLenzIdsEncodingAndDecoding() {
        val lineId = LenzIds.lineId("120.76.98.113", 8610, "395", 0, "武夷1路")
        assertEquals("lz:120.76.98.113:8610:395:0:武夷1路", lineId)
        assertTrue(LenzIds.isLenzId(lineId))

        val ref = LenzIds.parseLineId(lineId)
        assertNotNull(ref)
        assertEquals("120.76.98.113", ref.serverIp)
        assertEquals(8610, ref.serverPort)
        assertEquals("395", ref.routeId)
        assertEquals(0, ref.upDown)
        assertEquals("武夷1路", ref.lineName)

        val reversed = ref.reversed()
        assertEquals(1, reversed.upDown)

        val stopId = LenzIds.stopId("120.76.98.113", 8610, "1", "火车站")
        assertEquals("lz:120.76.98.113:8610:1:火车站", stopId)
        val stopParsed = LenzIds.parseStopId(stopId)
        assertNotNull(stopParsed)
        assertEquals("1", stopParsed.first)
        assertEquals("火车站", stopParsed.second)
    }

    @Test
    fun testFramePackingAndExtracting() {
        val payload = """{"type":0,"content":"1"}"""
        val frame = LenzApiClient.packFrame(83, payload)
        assertEquals(0x02.toByte(), frame[0])
        assertEquals(83.toByte(), frame[3])
        assertEquals(0x03.toByte(), frame.last())

        val extracted = LenzApiClient.extractJsonPayload(frame)
        assertEquals(payload, extracted)
    }

    /** 组一个应答帧：02 | s3 | cmd | 00 00 | [分片数 分片序号] | 载荷 | 00 FE 03 */
    private fun frameOf(cmd: Int, payload: ByteArray, sliceCount: Int = 1, sliceIndex: Int = 1): ByteArray {
        val multi = sliceCount > 1
        val s3 = payload.size or (if (multi) 1 shl 10 else 0)
        val header = byteArrayOf(
            0x02,
            ((s3 shr 8) and 0xFF).toByte(),
            (s3 and 0xFF).toByte(),
            cmd.toByte(),
            0x00,
            0x00
        )
        val ext = if (multi) {
            byteArrayOf(
                ((sliceCount shr 8) and 0xFF).toByte(),
                (sliceCount and 0xFF).toByte(),
                ((sliceIndex shr 8) and 0xFF).toByte(),
                (sliceIndex and 0xFF).toByte()
            )
        } else {
            ByteArray(0)
        }
        return header + ext + payload + byteArrayOf(0x00, 0xFE.toByte(), 0x03)
    }

    @Test
    fun testChinesePayloadIsGbkEncoded() {
        val frame = LenzApiClient.packFrame(83, "范县")
        // 「范县」的 GBK 是 B7 B6 CF D8
        assertTrue(frame.size > 8)
        assertEquals(0xB7.toByte(), frame[6])
        assertEquals(0xB6.toByte(), frame[7])
    }

    @Test
    fun testMultiFrameResponseIsReassembled() {
        val json = """{"serial":1,"Routes":[{"rid":947,"upDown":0,"rname":"范县至陆集乡"},{"rid":707,"upDown":1,"rname":"范县至王楼镇"}]}"""
        val bytes = lenzEncodeGbk(json)
        val cut = bytes.size / 2
        val raw = frameOf(83, bytes.copyOfRange(0, cut), sliceCount = 2, sliceIndex = 1) +
            frameOf(83, bytes.copyOfRange(cut, bytes.size), sliceCount = 2, sliceIndex = 2)

        val docs = LenzApiClient.extractJsonDocuments(raw)
        assertEquals(1, docs.size)
        assertTrue(docs[0].contains("范县至王楼镇"), "分片拼回来后中文应正常：${docs[0]}")
    }

    @Test
    fun testSearchTypeMatchesOriginalApp() {
        assertEquals(0, LenzApiClient.searchTypeOf("1"))
        assertEquals(0, LenzApiClient.searchTypeOf("K1"))
        assertEquals(1, LenzApiClient.searchTypeOf("范县"))
        assertEquals(0, LenzApiClient.searchTypeOf("范县1路"))
        assertEquals(0, LenzApiClient.searchTypeOf(""))
    }

    @Test
    fun testJsonStringEscapesQuotesAndBackslashes() {
        assertEquals("\"a\\\"b\"", LenzApiClient.jsonString("a\"b"))
        assertEquals("\"范县1路\"", LenzApiClient.jsonString("范县1路"))
    }

    @Test
    fun testCitiesXmlCarriesProvinceAndPrefecture() {
        val client = LenzApiClient(unusedHttpClient())
        val xml = """
            <items>
              <item><name>范县公交</name><serverIP>120.76.98.113</serverIP><serverPort>8619</serverPort><areaCode></areaCode></item>
              <item><name>清丰公交</name><serverIP>123.60.104.115</serverIP><serverPort>8625</serverPort><areaCode></areaCode></item>
              <item><name>武夷公交</name><serverIP>120.76.98.113</serverIP><serverPort>8610</serverPort><areaCode>350782</areaCode></item>
              <item><name>宁海城乡公交</name><serverIP>120.76.98.113</serverIP><serverPort>8611</serverPort><areaCode>330226</areaCode></item>
              <item><name>蓝斯公交</name><serverIP>192.168.1.62</serverIP><serverPort>2448</serverPort><areaCode></areaCode></item>
            </items>
        """.trimIndent()

        val cities = client.parseCitiesXml(xml)
        val fanxian = cities.first { it.cityName == "范县" }
        assertEquals("河南", fanxian.province)
        assertEquals("濮阳", fanxian.lenzRegion)
        assertEquals(8619, fanxian.lenzServerPort)

        assertEquals("濮阳", cities.first { it.cityName == "清丰" }.lenzRegion)
        assertEquals("南平", cities.first { it.cityName == "武夷" }.lenzRegion)
        assertEquals("宁波", cities.first { it.cityName == "宁海" }.lenzRegion)
        assertTrue(cities.none { it.cityName == "蓝斯" }, "内网地址条目应被剔除")
    }

    @Test
    fun testCitiesForSearchFallsBackToPrefectureCounties() = runTest {
        val client = LenzApiClient(unusedHttpClient())
        client.cachedCities = listOf(
            TransitCity("", "范县", lenzServerIp = "ip-fan", lenzServerPort = 8619, lenzRegion = "濮阳", province = "河南"),
            TransitCity("", "清丰", lenzServerIp = "ip-qing", lenzServerPort = 8625, lenzRegion = "濮阳", province = "河南"),
            TransitCity("", "武夷", lenzServerIp = "ip-wy", lenzServerPort = 8610, lenzRegion = "南平", province = "福建")
        )

        // 选中的是地级市「濮阳」：朗斯没有它的服务器，联查下属县
        val puyang = TransitCity("100", "濮阳", province = "河南")
        val servers = client.citiesForSearch(puyang)
        assertEquals(setOf("范县", "清丰"), servers.map { it.cityName }.toSet())

        // 选中的是朗斯城市本身：只打它自己
        val fanxian = client.cachedCities!!.first { it.cityName == "范县" }
        assertEquals(listOf("范县"), client.citiesForSearch(fanxian).map { it.cityName })
    }

    /**
     * 复现「紧挨着的两条记录实为一台车」：服务器对同一台车的多次 GPS 上报发不同 carID。
     * 取自范县 2 路实测数据——两条记录仅相距 7 米，应去重为一台；远处另一台不受影响。
     */
    @Test
    fun testAdjacentDuplicateReportsAreMergedIntoOneBus() {
        val carPoses = JSONArray(
            """
            [
              {"carID":100,"inOut":1,"staNO":10,"distance":340,"lat":35.744801,"lng":115.446685},
              {"carID":101,"inOut":1,"staNO":10,"distance":210,"lat":35.744818,"lng":115.446761},
              {"carID":200,"inOut":1,"staNO":1,"distance":8490,"lat":35.839855,"lng":115.509471}
            ]
            """.trimIndent()
        )
        val buses = LenzApiClient.parseLiveBuses(carPoses, stopCount = 21)
        // 7 米内的两条合并为一台，远处那台保留 → 共 2 台
        assertEquals(2, buses.size)
        // 合并时保留最靠前的上报（距下一站更近的 carID=101）
        assertTrue(buses.any { it.busId == "101" })
        assertTrue(buses.none { it.busId == "100" })
        assertTrue(buses.any { it.busId == "200" })
        // 途中车（inOut=1）目标站为 staNO+1；distance 字段不可靠，一律不展示
        val merged = buses.first { it.busId == "101" }
        assertEquals(11, merged.order)
        assertEquals(false, merged.isArrived)
        assertTrue(buses.all { it.distanceToTargetMeters == null })
    }

    @Test
    fun testAtStopBusKeepsStationOrderAndArrivedFlag() {
        val carPoses = JSONArray(
            """[{"carID":300,"inOut":0,"staNO":5,"distance":0,"lat":35.8,"lng":115.5}]"""
        )
        val buses = LenzApiClient.parseLiveBuses(carPoses, stopCount = 21)
        assertEquals(1, buses.size)
        assertEquals(5, buses[0].order)
        assertEquals(true, buses[0].isArrived)
    }
}
