package com.trackr.feature.bus.citypicker

import com.trackr.core.model.TransitCity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityPickerRowsTest {

    private fun chelaile(name: String, province: String) =
        TransitCity(cityId = "id_$name", cityName = name, province = province)

    private fun lenz(name: String, region: String, province: String) = TransitCity(
        cityId = "",
        cityName = name,
        province = province,
        lenzServerIp = "ip_$name",
        lenzServerPort = 8619,
        lenzRegion = region
    )

    @Test
    fun testCountiesNestUnderPrefecture() {
        val rows = buildCityPickerRows(
            listOf(
                chelaile("郑州", "河南"),
                chelaile("濮阳", "河南"),
                lenz("范县", "濮阳", "河南"),
                lenz("清丰", "濮阳", "河南"),
                lenz("台前", "濮阳", "河南")
            )
        )

        // 郑州、濮阳 + 濮阳下面三个县 = 5 行
        assertEquals(5, rows.size)
        val puyang = rows[1] as CityPickerRow.City
        assertEquals("濮阳", puyang.city.cityName)
        assertEquals(3, puyang.countyCount)
        assertEquals(
            listOf("范县", "清丰", "台前"),
            rows.drop(2).filterIsInstance<CityPickerRow.County>().map { it.city.cityName }
        )
    }

    @Test
    fun testCountyWhosePrefectureIsMissingGetsHeader() {
        val rows = buildCityPickerRows(
            listOf(
                chelaile("郑州", "河南"),
                lenz("武夷", "南平", "福建")
            )
        )

        assertEquals(3, rows.size)
        assertTrue(rows[0] is CityPickerRow.City)
        val header = rows[1] as CityPickerRow.PrefectureHeader
        assertEquals("南平", header.name)
        assertEquals("福建", header.province)
        assertEquals("武夷", (rows[2] as CityPickerRow.County).city.cityName)
    }

    @Test
    fun testPrefectureLevelLenzCityStaysAtTopLevel() {
        val rows = buildCityPickerRows(
            listOf(lenz("福州", "福州", "福建"), lenz("武夷", "南平", "福建"))
        )

        val topLevel = rows.filterIsInstance<CityPickerRow.City>().map { it.city.cityName }
        assertEquals(listOf("福州"), topLevel)
        assertEquals("南平", (rows.first { it is CityPickerRow.PrefectureHeader } as CityPickerRow.PrefectureHeader).name)
    }

    @Test
    fun testCitySupportedByTwoSourcesKeepsSingleRow() {
        val merged = TransitCity(
            cityId = "023",
            cityName = "鹤壁",
            province = "河南",
            mygolbsCityCode = "410600"
        )
        val rows = buildCityPickerRows(listOf(merged))
        assertEquals(1, rows.size)
        assertEquals("鹤壁", (rows[0] as CityPickerRow.City).city.cityName)
    }
}
