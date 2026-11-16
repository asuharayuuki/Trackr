package com.trackr.core.network.remote.lenz

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.remote.common.BaseTransitApiClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.time.Clock

/**
 * 朗斯通信「掌上出行」数据源 API 客户端
 * 涵盖：
 * 1. 城市列表远程拉取与本地容灾缓存（XML 解析）
 * 2. 线路搜索（TCP CMD 83）
 * 3. 线路详情与站点列表（TCP CMD 81）
 * 4. 实时在途车辆位置与状态（TCP CMD 84）
 */
class LenzApiClient(
    httpClient: HttpClient
) : BaseTransitApiClient(httpClient) {

    companion object {
        const val CITY_INDEX_URL = "http://222.76.217.238:8090/App/MyBus/index.xml"

        /** 城市归属：省（Trackr 短名）+ 地级市 */
        internal data class CityMeta(val province: String, val region: String)

        /** 上级地级市编码（areaCode 前 4 位）；县级市按其所属地级市归类 */
        internal val PREFECTURE_BY_CODE4: Map<String, CityMeta> = mapOf(
            "3501" to CityMeta("福建", "福州"),
            "3502" to CityMeta("福建", "厦门"),
            "3503" to CityMeta("福建", "莆田"),
            "3504" to CityMeta("福建", "三明"),
            "3507" to CityMeta("福建", "南平"),
            "3508" to CityMeta("福建", "龙岩"),
            "3302" to CityMeta("浙江", "宁波"),
            "3308" to CityMeta("浙江", "衢州"),
            "4105" to CityMeta("河南", "安阳"),
            "4109" to CityMeta("河南", "濮阳"),
            "4114" to CityMeta("河南", "商丘"),
            "4120" to CityMeta("湖南", "郴州"),
            "4302" to CityMeta("湖南", "株洲"),
            "4507" to CityMeta("广西", "钦州"),
            "6105" to CityMeta("陕西", "渭南"),
            "5133" to CityMeta("四川", "甘孜"),
            "1308" to CityMeta("河北", "承德"),
            "1310" to CityMeta("河北", "廊坊"),
            "6403" to CityMeta("宁夏", "吴忠"),
            "4228" to CityMeta("湖北", "恩施"),
            "4209" to CityMeta("湖北", "孝感"),
            "6523" to CityMeta("新疆", "昌吉"),
            "2101" to CityMeta("辽宁", "沈阳"),
            "2102" to CityMeta("辽宁", "大连"),
            "8200" to CityMeta("港澳台", "澳门"),
            "6206" to CityMeta("甘肃", "武威"),
            "6210" to CityMeta("甘肃", "庆阳"),
            "3401" to CityMeta("安徽", "合肥"),
            "3408" to CityMeta("安徽", "安庆"),
            "3413" to CityMeta("安徽", "宿州"),
            "3418" to CityMeta("安徽", "宣城"),
            "3712" to CityMeta("山东", "莱芜"),
            "3707" to CityMeta("山东", "潍坊"),
            "3212" to CityMeta("江苏", "泰州"),
            "3207" to CityMeta("江苏", "连云港"),
            "2201" to CityMeta("吉林", "长春")
        )

        /** areaCode 缺失的条目：按名称补省与上级地级市 */
        internal val NAME_META: Map<String, CityMeta> = mapOf(
            "范县" to CityMeta("河南", "濮阳"),
            "清丰" to CityMeta("河南", "濮阳"),
            "台前" to CityMeta("河南", "濮阳"),
            "滑县" to CityMeta("河南", "安阳"),
            "长顺" to CityMeta("贵州", "黔南"),
            "楚雄" to CityMeta("云南", "楚雄"),
            "巴林右旗" to CityMeta("内蒙古", "赤峰"),
            "全椒" to CityMeta("安徽", "滁州"),
            "定远" to CityMeta("安徽", "滁州"),
            "涡阳" to CityMeta("安徽", "亳州"),
            "屏南" to CityMeta("福建", "宁德"),
            "信丰" to CityMeta("江西", "赣州"),
            "河源" to CityMeta("广东", "河源"),
            "涿鹿" to CityMeta("河北", "张家口"),
            "唐山汉沽" to CityMeta("河北", "唐山"),
            "秦皇岛" to CityMeta("河北", "秦皇岛"),
            "自贡" to CityMeta("四川", "自贡"),
            "仰光" to CityMeta("其他", "仰光")
        )

        /** 城市名拼音，供城市选择器按拼音搜索（官方清单里没有这个字段） */
        private val PINYIN: Map<String, String> = mapOf(
            "福州" to "fuzhou", "宁海" to "ninghai", "永城" to "yongcheng", "郴州" to "chenzhou",
            "武夷" to "wuyi", "钦州" to "qinzhou", "渭南" to "weinan", "康定" to "kangding",
            "滦平" to "luanping", "吴忠" to "wuzhong", "咸丰" to "xianfeng", "昌吉" to "changji",
            "Pekanbaru" to "pekanbaru", "三明将乐" to "sanmingjiangle", "莆田仙游" to "putianxianyou",
            "辽宁城际" to "liaoningchengji", "利川" to "lichuan", "滑县" to "huaxian",
            "澳门新福利" to "aomenxinfuli", "武威" to "wuwei", "香河" to "xianghe",
            "怀宁" to "huaining", "莱芜长运" to "laiwuchangyun", "潍坊交运" to "weifangjiaoyun",
            "林州" to "linzhou", "大连昌赫" to "dalianchanghe", "仰光" to "yangguang",
            "兴化" to "xinghua", "郎溪" to "langxi", "肥西" to "feixi", "泗县" to "sixian",
            "赣榆" to "ganyu", "醴陵" to "liling", "开化" to "kaihua", "长春寰旗" to "changchunhuanqi",
            "漳平" to "zhangping", "上杭" to "shanghang", "武平" to "wuping", "永定" to "yongding",
            "长汀" to "changting", "连城" to "liancheng", "环县" to "huanxian", "汉川" to "hanchuan",
            "周宁" to "zhouning", "楚雄" to "chuxiong", "巴林右旗" to "balinyouqi",
            "全椒" to "quanjiao", "屏南" to "pingnan", "信丰" to "xinfeng", "涡阳" to "guoyang",
            "长顺" to "changshun", "范县" to "fanxian", "河源" to "heyuan", "定远" to "dingyuan",
            "涿鹿" to "zhuolu", "自贡" to "zigong", "唐山汉沽" to "tangshanhangu",
            "清丰" to "qingfeng", "台前" to "taiqian", "秦皇岛" to "qinhuangdao"
        )

        /** 线路号 + 需要补充说明时挂在名字后面的括号 */
        internal data class RouteTag(val number: String, val note: String? = null)

        /**
         * 城市内的线路号（「几路车」）。
         *
         * 朗斯协议只回线路名（如「范县至王楼镇」），不带 1路/2路 这种编号。
         * 这里按范县城乡公交公众号公布的冬季时间表，**逐条**把 25 条线路对上 1-12 路：
         * - 常见一个线路号对应多台车跑同一条线，所以不会重复使用；
         * - 时间表里没有的线路（旅游专线、城际线、支线）一律不编号，避免张冠李戴；
         * - 同一线路号确实有两套跑法时（8 路东行/南行、11 路两条走向），
         *   用括号把区别标出来。
         */
        private val ROUTE_NUMBERS: Map<String, Map<String, RouteTag>> = mapOf(
            "范县" to mapOf(
                "范县至王楼镇" to RouteTag("1路"),
                "范县至濮城镇" to RouteTag("2路"),
                "范县至彭楼水库" to RouteTag("3路"),
                "范县至辛庄" to RouteTag("4路"),
                "范县至杨集乡" to RouteTag("5路"),
                "范县至陈庄" to RouteTag("6路"),
                "范县至龙王庄镇" to RouteTag("7路"),
                "范县至陆集乡（线路1东行）" to RouteTag("8路"),
                "范县至陆集乡（线路2南行）" to RouteTag("8路"),
                "范县至张庄乡" to RouteTag("9路"),
                "范县至颜村铺乡" to RouteTag("10路"),
                "范县至陈庄（石大庙）" to RouteTag("12路"),
                "范县至高码头镇" to RouteTag("11路", "线路1"),
                "范县-仲庄-高码头" to RouteTag("11路", "线路2")
            )
        )

        /** 这条线路是几路车；时间表里没有对应就返回 null */
        internal fun routeNumberFor(cityName: String?, routeName: String): String? =
            cityName?.let { ROUTE_NUMBERS[it]?.get(routeName) }?.number

        /** 展示名：「1路 范县至王楼镇」，有需要说明的再补「（…）」；没有对应就保持原名 */
        internal fun displayRouteName(cityName: String?, routeName: String): String {
            val tag = cityName?.let { ROUTE_NUMBERS[it]?.get(routeName) } ?: return routeName
            val base = if (routeName.startsWith(tag.number)) routeName else "${tag.number} $routeName"
            return if (tag.note.isNullOrBlank()) base else "$base（${tag.note}）"
        }

        /** 内网地址、演示/测试条目，装机后连不上，直接排除 */
        private val UNREACHABLE_HOSTS = setOf("192.168.1.62")
        private val SKIP_NAMES = setOf("AA旅游有限公司", "天迈公交", "好运巴士", "蓝斯公交", "路安机电", "天迈", "好运")

        /**
         * 离线兜底城市表：线上清单拉不到（明文 HTTP 被拦、接口不可达）时用它，
         * 由 index.xml 生成，城市与县级齐全，见 [BUNDLED_LENZ_CITIES]。
         */
        val FALLBACK_CITIES: List<TransitCity> get() = BUNDLED_LENZ_CITIES

        /** 按名称创建城市条目；省份与上级地级市可直接给，缺省时按名称/区划码推断 */
        fun cityOf(
            name: String,
            ip: String,
            port: Int,
            areaCode: String,
            province: String = "",
            region: String = ""
        ): TransitCity {
            val meta = when {
                province.isNotBlank() || region.isNotBlank() ->
                    CityMeta(province, region.ifBlank { name })
                else -> metaFor(name, areaCode)
            }
            return TransitCity(
                cityId = "",
                cityName = name,
                lenzServerIp = ip,
                lenzServerPort = port,
                lenzAreaCode = areaCode.ifBlank { null },
                lenzRegion = meta?.region,
                province = meta?.province.orEmpty()
            )
        }

        private fun metaFor(name: String, areaCode: String): CityMeta? {
            NAME_META[name]?.let { return it }
            if (areaCode.length >= 4) {
                PREFECTURE_BY_CODE4[areaCode.take(4)]?.let { return it }
            }
            return null
        }

        /**
         * 线路的运营信息缓存（线路 id -> 首班/末班/下一班）。
         *
         * 朗斯没有「发车时刻表」接口：和原版掌上出行 App 一样，只有
         * - 检索线路时随线路返回的首班 `fdtime` / 末班 `ldtime`；
         * - 查车辆位置时返回的 `ndTime`（下一班计划发车时间）。
         * 这里按线路 id 存下来，详情页和时刻表弹窗直接用。
         */
        internal data class LineMeta(
            val firstTime: String = "",
            val lastTime: String = "",
            val nextDeparture: String = ""
        )

        /** 组帧：载荷一律按 GBK 编码（服务器只认 GBK，UTF-8 的中文关键词会被丢弃） */
        fun packFrame(cmd: Int, payload: String): ByteArray {
            val payloadBytes = lenzEncodeGbk(payload)
            val len = payloadBytes.size
            val header = byteArrayOf(0x02, 0x00, len.toByte(), cmd.toByte(), 0x00, 0x01)
            val tail = byteArrayOf(0x00, 0xFE.toByte(), 0x03)
            return header + payloadBytes + tail
        }

        /**
         * 检索类型（和原 App 一致）：
         * - 纯数字 / 字母+数字（线路号）→ 0
         * - 纯中文且不含「线」→ 1（会连途经站一起匹配，结果更多）
         * - 其余 → 0
         */
        internal fun searchTypeOf(keyword: String): Int {
            val k = keyword.trim()
            if (k.isEmpty()) return 0
            if (k.all { it.isDigit() }) return 0
            if (k[0].isLetter() && k[0].code < 128 && k.drop(1).all { it.isDigit() }) return 0
            val allChinese = k.all { it in '一'..'龥' }
            return if (allChinese && !k.contains("线")) 1 else 0
        }

        /** 把任意文本安全地放进 JSON 字符串字面量（线路名可能带引号、反斜杠） */
        internal fun jsonString(value: String): String = buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) {
                        append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        append(c)
                    }
                }
            }
            append('"')
        }

        /** 取应答里的第一个 JSON 文档（单帧场景）；长应答请用 [LenzFrames.extractJsonDocuments] */
        fun extractJsonPayload(bytes: ByteArray): String? = extractJsonDocuments(bytes).firstOrNull()

        /** 长应答会被切成多帧下发，这里拿到按分片拼好的完整 JSON 列表 */
        fun extractJsonDocuments(bytes: ByteArray): List<String> =
            LenzFrames.extractJsonDocuments(bytes).mapNotNull { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start == -1 || end <= start) null else text.substring(start, end + 1)
            }

        /** 第一个带指定数组字段的 JSON 文档（站表 / 车辆列表） */
        internal fun docWithList(docs: List<String>, key: String): JSONObject? {
            for (doc in docs) {
                val obj = try { JSONObject(doc) } catch (_: Exception) { null } ?: continue
                if (obj.optJSONArray(key) != null) return obj
            }
            return null
        }

        /**
         * 把 CMD 84 的 carPoses 解析成实时车辆列表。
         *
         * 服务器对同一台车的每次 GPS 上报都发一个新 carID（内部句柄，不是稳定车号），
         * 因此紧挨着的两条记录很可能是同一台车：按位置邻近（200 米内）去重，保留最靠前的一条。
         * 服务器的 distance 字段不可靠（可超过全程长度），原版 App 也不使用，故不展示。
         */
        internal fun parseLiveBuses(carPoses: JSONArray?, stopCount: Int): List<LiveBus> {
            if (carPoses == null) return emptyList()

            data class CarEntry(
                val carId: String,
                val inOut: Int,
                val staNO: Int,
                val distance: Int,
                val lat: Double,
                val lng: Double
            )

            val entries = mutableListOf<CarEntry>()
            for (i in 0 until carPoses.length()) {
                val cObj = carPoses.optJSONObject(i) ?: continue
                entries.add(
                    CarEntry(
                        carId = cObj.optInt("carID", i + 1).toString(),
                        inOut = cObj.optInt("inOut", 1), // 0: 停在 staNO 站，1: 已过 staNO 站、正驶向下一站
                        staNO = cObj.optInt("staNO", 1),
                        distance = cObj.optInt("distance", 0),
                        lat = cObj.optDouble("lat", 0.0),
                        lng = cObj.optDouble("lng", 0.0)
                    )
                )
            }

            // 保留最靠前的上报（站序大、距下一站近），其余 200 米内的视为同一台车的重复上报丢弃
            val kept = mutableListOf<CarEntry>()
            for (e in entries.sortedWith(compareByDescending<CarEntry> { it.staNO }.thenBy { it.distance })) {
                val isDuplicate = kept.any { k ->
                    val d = RouteProjectionMath.calculateDistanceMeters(e.lat, e.lng, k.lat, k.lng)
                    d != null && d <= 200
                }
                if (!isDuplicate) kept.add(e)
            }

            return kept.map { e ->
                // LiveBus.order 表示「正在接近/已停靠的目标站」：在站内就是 staNO，途中是 staNO + 1
                val targetOrder = if (e.inOut == 0) {
                    e.staNO
                } else {
                    (e.staNO + 1).coerceAtMost(stopCount.coerceAtLeast(1)).coerceAtLeast(1)
                }
                LiveBus(
                    busId = e.carId,
                    // 协议只给系统内部车号，对乘客没有意义，统一显示「车辆」
                    plateOrFleetNo = "车辆",
                    order = targetOrder,
                    isArrived = (e.inOut == 0),
                    distanceToTargetMeters = null,
                    wgsLat = e.lat,
                    wgsLng = e.lng
                )
            }
        }
    }

    /** 城市表缓存；测试可预置，避免连真实服务器 */
    internal var cachedCities: List<TransitCity>? = null

    /** 线路运营信息（首末班 / 下一班发车），按线路 id 存；随查询顺带更新 */
    private val lineMeta = LinkedHashMap<String, LineMeta>()

    /** 取线路的首末班与下一班发车时间；没有就返回空串 */
    internal fun lineMetaOf(lineId: String): LineMeta = lineMeta[lineId] ?: LineMeta()

    private fun rememberLineMeta(lineId: String, firstTime: String, lastTime: String) {
        val old = lineMeta[lineId]
        if (firstTime.isBlank() && lastTime.isBlank()) return
        lineMeta[lineId] = LineMeta(
            firstTime = firstTime.ifBlank { old?.firstTime.orEmpty() },
            lastTime = lastTime.ifBlank { old?.lastTime.orEmpty() },
            nextDeparture = old?.nextDeparture.orEmpty()
        )
        if (lineMeta.size > 200) {
            lineMeta.remove(lineMeta.keys.first())
        }
    }

    private fun rememberNextDeparture(lineId: String, nextDeparture: String) {
        val old = lineMeta[lineId] ?: LineMeta()
        lineMeta[lineId] = old.copy(nextDeparture = nextDeparture)
    }

    /** 可达性探测结果缓存：cityName -> (是否在线, 探测时刻毫秒) */
    private val reachability = LinkedHashMap<String, Pair<Boolean, Long>>()

    /**
     * 探测某台城市服务器能不能连上。
     *
     * 掌上出行是「一个城市一台服务器」，服务器下线时查询只会得到空结果，
     * 用户会误以为「App 拿不到数据」。这里真连一次，用来在界面上把离线城市标出来。
     */
    suspend fun isServerReachable(city: TransitCity, ttlMs: Long = 3 * 60 * 1000L): Boolean {
        val ip = city.lenzServerIp ?: return true
        val port = city.lenzServerPort ?: return true
        val key = "${city.cityName}@$ip:$port"
        val now = Clock.System.now().toEpochMilliseconds()
        reachability[key]?.let { (online, at) ->
            if (now - at < ttlMs) return online
        }
        val online = lenzSocketProbe(ip, port, 2500)
        reachability[key] = online to now
        return online
    }

    /**
     * 解析一次检索要打的服务器：
     * - 选中的是朗斯城市 → 只打它自己的服务器；
     * - 选中的是地级市（如「濮阳」，朗斯没有独立服务器）→ 打该地级市下所有县级服务器（范县 / 清丰 / 台前…）。
     */
    suspend fun citiesForSearch(city: TransitCity, maxServers: Int = 4): List<TransitCity> {
        if (!city.lenzServerIp.isNullOrBlank() && city.lenzServerPort != null) {
            return listOf(city)
        }
        val raw = city.cityName.trim()
        val clean = ProvinceCityCatalog.cleanCityName(raw)
        if (raw.isBlank() && clean.isBlank()) return emptyList()
        return getCities()
            .filter { it.lenzServerPort != null }
            .filter {
                it.cityName == raw || it.cityName == clean ||
                    it.lenzRegion == raw || it.lenzRegion == clean
            }
            .take(maxServers)
    }

    /**
     * 获取朗斯支持的所有城市。
     *
     * 清单是明文 HTTP，先走原始 socket（不受系统明文策略拦截），再退回 OkHttp，
     * 两条路都失败时用内置目录 [BUNDLED_LENZ_CITIES]，保证县级城市永远可选。
     * 线上清单拿到的条目若缺省份，用内置目录按名字补齐（清单里没有县一级归属字段）。
     */
    suspend fun getCities(): List<TransitCity> {
        cachedCities?.let { return it }

        val xml = runCatching { lenzHttpGetText(CITY_INDEX_URL) }.getOrNull()
            ?: runCatching { httpClient.get(CITY_INDEX_URL).bodyAsText() }.getOrNull()

        val remote = xml?.let { runCatching { parseCitiesXml(it) }.getOrNull() }.orEmpty()
        val list = if (remote.isNotEmpty()) {
            val bundledByName = BUNDLED_LENZ_CITIES.associateBy { it.cityName }
            remote.map { city ->
                val known = bundledByName[city.cityName]
                if (city.province.isBlank() && known != null) {
                    city.copy(province = known.province, lenzRegion = city.lenzRegion ?: known.lenzRegion)
                } else {
                    city
                }
            }
        } else {
            BUNDLED_LENZ_CITIES
        }

        // 官方清单没有拼音，补上，城市选择器才能按拼音找
        val withPinyin = list.map { city ->
            if (city.pinyin.isBlank()) city.copy(pinyin = PINYIN[city.cityName].orEmpty()) else city
        }

        cachedCities = withPinyin
        return withPinyin
    }

    internal fun parseCitiesXml(xml: String): List<TransitCity> {
        val result = mutableListOf<TransitCity>()
        val itemPattern = Regex("""<item>([\s\S]*?)</item>""")
        val namePattern = Regex("""<name>(.*?)</name>""")
        val ipPattern = Regex("""<serverIP>(.*?)</serverIP>""")
        val portPattern = Regex("""<serverPort>(.*?)</serverPort>""")
        val areaPattern = Regex("""<areaCode>(.*?)</areaCode>""")

        for (match in itemPattern.findAll(xml)) {
            val itemContent = match.groupValues[1]
            val rawName = namePattern.find(itemContent)?.groupValues?.get(1)?.trim().orEmpty()
            val ip = ipPattern.find(itemContent)?.groupValues?.get(1)?.trim().orEmpty()
            val portStr = portPattern.find(itemContent)?.groupValues?.get(1)?.trim().orEmpty()
            val areaCode = areaPattern.find(itemContent)?.groupValues?.get(1)?.trim().orEmpty()
            val port = portStr.toIntOrNull() ?: continue
            if (rawName.isBlank() || ip.isBlank()) continue
            if (ip in UNREACHABLE_HOSTS) continue

            val cleanName = rawName
                .removeSuffix("城乡公交")
                .removeSuffix("公交")
                .removeSuffix("客运")
                .removeSuffix("巴士")
                .removeSuffix("市")
                .trim()
            if (cleanName.isBlank() || cleanName in SKIP_NAMES) continue

            result.add(cityOf(cleanName, ip, port, areaCode))
        }
        return result
    }

    /**
     * 搜索线路
     */
    suspend fun searchLines(
        serverIp: String,
        serverPort: Int,
        keyword: String,
        /** 城市名：用来给线路补「几路车」编号 */
        cityName: String? = null
    ): List<BusLineSummary> {
        val payload = """{"type":${searchTypeOf(keyword)},"content":${jsonString(keyword)}}"""
        val reqData = packFrame(83, payload)
        val respBytes = lenzSocketSendAndReceive(serverIp, serverPort, reqData) ?: return emptyList()
        val docs = extractJsonDocuments(respBytes)
        if (docs.isEmpty()) return emptyList()

        val routeGroups = LinkedHashMap<String, MutableList<BusLineDirection>>()

        for (doc in docs) {
            val root = try { JSONObject(doc) } catch (_: Exception) { continue }
            val routes = root.optJSONArray("Routes") ?: continue
            for (i in 0 until routes.length()) {
                val routeObj = routes.optJSONObject(i) ?: continue
                val rid = routeObj.optInt("rid", 0).toString()
                val upDown = routeObj.optInt("upDown", 0)
                val rname = routeObj.optString("rname", "")
                val fstname = routeObj.optString("fstname", "")
                val lstname = routeObj.optString("lstname", "")
                val firstTime = routeObj.optString("fdtime", "")
                val lastTime = routeObj.optString("ldtime", "")
                if (rid == "0" || rname.isBlank()) continue

                // 「1路 范县至王楼镇」：把线路号标出来，和车辆自编号区分开
                val displayName = displayRouteName(cityName, rname)
                val lineId = LenzIds.lineId(serverIp, serverPort, rid, upDown, displayName)
                rememberLineMeta(lineId, firstTime, lastTime)
                val dirs = routeGroups.getOrPut(displayName) { mutableListOf() }
                // 同一线路方向可能被多个分片重复下发
                if (dirs.any { it.direction == upDown }) continue
                dirs.add(
                    BusLineDirection(
                        lineId = lineId,
                        name = displayName,
                        lineNo = routeNumberFor(cityName, rname) ?: rname,
                        direction = upDown,
                        startStop = fstname,
                        endStop = lstname,
                        firstTime = firstTime,
                        lastTime = lastTime,
                        source = DataSourceType.LENZ
                    )
                )
            }
        }

        return routeGroups.map { (name, dirs) ->
            BusLineSummary(
                name = name,
                lineNo = name,
                directions = dirs,
                source = DataSourceType.LENZ
            )
        }
    }

    /**
     * 获取线路详情与站点列表，并融合实时到站车辆
     */
    suspend fun getLineDetail(
        lineRef: LenzIds.LineRef
    ): BusLineDetail {
        val (serverIp, serverPort, routeId, upDown, lineName) = lineRef

        // 1. 查询站点
        val stationPayload = """{"rid":${routeId.toIntOrNull() ?: 0},"updown":$upDown}"""
        val stationReq = packFrame(81, stationPayload)
        val stationResp = lenzSocketSendAndReceive(serverIp, serverPort, stationReq)
        val stationsArray = stationResp
            ?.let { docWithList(extractJsonDocuments(it), "stations") }
            ?.optJSONArray("stations")

        val stops = mutableListOf<BusStop>()
        if (stationsArray != null) {
            for (i in 0 until stationsArray.length()) {
                val sObj = stationsArray.optJSONObject(i) ?: continue
                val staNO = sObj.optInt("staNO", i + 1)
                val staName = sObj.optString("staName", "")
                val staLat = sObj.optDouble("staLat", 0.0) / 1000000.0
                val staLng = sObj.optDouble("staLng", 0.0) / 1000000.0
                stops.add(
                    BusStop(
                        stopId = LenzIds.stopId(serverIp, serverPort, staNO.toString(), staName),
                        name = staName,
                        order = staNO,
                        wgsLat = staLat,
                        wgsLng = staLng
                    )
                )
            }
        }

        // 2. 查询实时车辆位置
        val busPayload = """{"rid":${routeId.toIntOrNull() ?: 0},"upDown":$upDown,"staNO":${stops.size},"number":0}"""
        val busReq = packFrame(84, busPayload)
        val busResp = lenzSocketSendAndReceive(serverIp, serverPort, busReq)
        val busDocs = busResp?.let { extractJsonDocuments(it) }.orEmpty()
        val busDoc = docWithList(busDocs, "carPoses")
        val carPoses = busDoc?.optJSONArray("carPoses")
        // 下一班计划发车时间，和原版掌上出行 App 一样从车载数据里带回来
        val nextDeparture = busDoc?.optString("ndTime", "").orEmpty().trim()
        if (nextDeparture.isNotBlank()) {
            rememberNextDeparture(lineRef.lineId(), nextDeparture)
        }

        val liveBuses = parseLiveBuses(carPoses, stops.size)

        val firstStop = stops.firstOrNull()?.name.orEmpty()
        val lastStop = stops.lastOrNull()?.name.orEmpty()

        val meta = lineMetaOf(lineRef.lineId())
        val direction = BusLineDirection(
            lineId = lineRef.lineId(),
            name = lineName,
            lineNo = lineName,
            direction = upDown,
            startStop = firstStop,
            endStop = lastStop,
            firstTime = meta.firstTime,
            lastTime = meta.lastTime,
            source = DataSourceType.LENZ
        )

        // 反方向条目：只给 id 和起终点（反方向的站表等切过去再查）
        val reversedRef = lineRef.reversed()
        val reverseDirection = direction.copy(
            lineId = reversedRef.lineId(),
            direction = reversedRef.upDown,
            startStop = lastStop,
            endStop = firstStop
        )

        return BusLineDetail(
            line = direction,
            reverseDirection = reverseDirection,
            stops = stops,
            buses = liveBuses,
            operatingState = if (liveBuses.isNotEmpty()) LineOperatingState.RUNNING else LineOperatingState.WAITING,
            nextDepartureFromStart = nextDeparture.ifBlank { null }
        )
    }
}
