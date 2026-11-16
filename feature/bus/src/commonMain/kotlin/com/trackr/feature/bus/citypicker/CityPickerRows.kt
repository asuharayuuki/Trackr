package com.trackr.feature.bus.citypicker

import com.trackr.core.model.TransitCity

/**
 * 城市选择器右侧列表的一行：省 → 地级市 → 县级市/县 三级。
 *
 * 三个数据源的城市粒度不一样：车来了、掌上公交基本只到地级市，
 * 掌上出行大量是县级（范县、清丰、台前…）。这里把县级条目挂到所属地级市下面，
 * 地级市本身不在列表里时补一个不可点的分组标题，保证层级不乱。
 */
sealed interface CityPickerRow {
    val key: String

    /** 地级市本级（可选中）；[countyCount] > 0 时下面会跟县级条目 */
    data class City(val city: TransitCity, val countyCount: Int = 0) : CityPickerRow {
        override val key: String get() = "city_${city.uniqueKey}"
    }

    /** 县级城市（可选中，缩进一级显示） */
    data class County(val city: TransitCity) : CityPickerRow {
        override val key: String get() = "county_${city.uniqueKey}"
    }

    /** 上级地级市本身不在城市列表里时的分组标题（不可点） */
    data class PrefectureHeader(val name: String, val province: String) : CityPickerRow {
        override val key: String get() = "header_$name"
    }
}

/** 朗斯带来的县级城市：有上级地级市且和上级不同名 */
fun TransitCity.isCountyLevel(): Boolean {
    val region = lenzRegion?.trim().orEmpty()
    return region.isNotBlank() && region != cityName.trim()
}

/** 展示用的「所属地级市」；地级市本级返回空串 */
fun TransitCity.prefectureTrail(): String = if (isCountyLevel()) lenzRegion.orEmpty() else ""

/**
 * 把一个省下的城市摊平成带层级的行。
 *
 * 顺序：按传入顺序遍历城市——遇到县先跳过，等父级出现时紧跟其后再补上；
 * 父级不在列表里的县，在末尾按地级市名归组，前面加一个分组标题。
 */
fun buildCityPickerRows(cities: List<TransitCity>): List<CityPickerRow> {
    val countiesByPrefecture = cities
        .filter { it.isCountyLevel() && !it.lenzServerIp.isNullOrBlank() }
        .groupBy { it.lenzRegion.orEmpty() }
    val countyNames = countiesByPrefecture.values.flatten().map { it.cityName }.toSet()

    val rows = ArrayList<CityPickerRow>(cities.size)
    val handledPrefectures = HashSet<String>()

    for (city in cities) {
        if (city.cityName in countyNames) continue
        val counties = countiesByPrefecture[city.cityName].orEmpty()
        rows.add(CityPickerRow.City(city, counties.size))
        if (counties.isNotEmpty()) {
            handledPrefectures.add(city.cityName)
            counties.forEach { rows.add(CityPickerRow.County(it)) }
        }
    }

    for ((prefecture, counties) in countiesByPrefecture) {
        if (prefecture.isBlank() || prefecture in handledPrefectures) continue
        val province = counties.firstOrNull()?.province.orEmpty()
        rows.add(CityPickerRow.PrefectureHeader(prefecture, province))
        counties.forEach { rows.add(CityPickerRow.County(it)) }
    }

    return rows
}
