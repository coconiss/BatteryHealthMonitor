// data/remote/GsmArenaScraperService.kt
package com.batteryhealth.monitor.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GsmArenaScraperService @Inject constructor() {

    /**
     * GSMArena에서 기기 검색 및 배터리 용량 추출
     */
    suspend fun searchBatteryCapacity(
        manufacturer: String,
        model: String
    ): Int? = withContext(Dispatchers.IO) {
        try {
            // 1. 검색 페이지
            val searchQuery = "$manufacturer $model".replace(" ", "+")
            val searchUrl = "https://www.gsmarena.com/res.php3?sSearch=$searchQuery"

            val searchDoc = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get()

            // 2. 첫 번째 결과 링크 찾기
            val deviceLink = searchDoc.select("div.makers a").firstOrNull()?.attr("href")
                ?: return@withContext null

            val deviceUrl = "https://www.gsmarena.com/$deviceLink"

            // 3. 기기 상세 페이지
            val deviceDoc = Jsoup.connect(deviceUrl)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get()

            // 4. 배터리 용량 파싱
            val batteryInfo = deviceDoc.select("td[data-spec='batdescription1']")
                .text()

            // "5000 mAh" 형식에서 숫자 추출
            val regex = """(\d+)\s*mAh""".toRegex()
            val matchResult = regex.find(batteryInfo)

            matchResult?.groupValues?.get(1)?.toIntOrNull()

        } catch (e: Exception) {
            Timber.e(e, "Failed to scrape GSMArena")
            null
        }
    }
}