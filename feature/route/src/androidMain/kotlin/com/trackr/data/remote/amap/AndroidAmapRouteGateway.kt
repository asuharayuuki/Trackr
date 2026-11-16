package com.trackr.data.remote.amap

import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.AmapRouteGateway
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.network.remote.amap.AmapTransitApiClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Android 的高德通道：SDK 优先；只有 SDK 调用失败（未配置 Key / 网络 / 鉴权等）才回落 Web 服务。
 * SDK 成功但结果为空时直接返回空结果，不再用 Web 重复请求。
 *
 * 第 17 步从 `FavoritesGroupViewModel` 的内部类原样搬来；Key 每次现读 [amapKeys]（「我的 → 高德 Key」改了马上生效）。
 * iOS 用 `:core:data` 里只走 Web 的 `WebAmapRouteGateway`。
 */
class AndroidAmapRouteGateway(
    private val amapSdkService: AmapSdkTransitService?,
    private val amapApiClient: AmapTransitApiClient?,
    private val amapKeys: AmapKeyRepository?
) : AmapRouteGateway {

    private val customAmapApiKey: String? get() = amapKeys?.androidKey?.value

    /** 当前生效的 Web 服务 Key：判断可用与实际发请求统一用它（用户配置 > BuildConfig） */
    private fun effectiveWebServiceKey(): String =
        AmapSdkManager.getEffectiveWebServiceKey(amapKeys?.webKey?.value)

    private fun sdkUsable(): Boolean =
        amapKeys?.privacyConsent?.value == true && amapSdkService != null && AmapSdkManager.isAndroidKeyConfigured(customAmapApiKey)

    override fun isConfigured(): Boolean = amapKeys?.privacyConsent?.value == true && (sdkUsable() || effectiveWebServiceKey().isNotEmpty())

    override suspend fun searchTips(
        keyword: String,
        cityName: String,
        location: String?
    ): Result<List<AmapTipItem>> {
        if (amapKeys?.privacyConsent?.value != true) return Result.failure(IllegalStateException("未同意高德隐私说明"))
        var sdkFailure: Throwable? = null
        val sdk = amapSdkService
        if (sdk != null && sdkUsable()) {
            val result = sdk.searchPoiTips(keyword, cityName, customAmapApiKey, location)
            currentCoroutineContext().ensureActive()
            if (result.isSuccess) return result
            sdkFailure = result.exceptionOrNull()
        }
        val webKey = effectiveWebServiceKey()
        val web = amapApiClient
        if (webKey.isNotEmpty() && web != null) {
            return web.searchPoiTips(keyword, cityName, webKey, location)
        }
        return Result.failure(sdkFailure ?: IllegalStateException("未配置高德 Key"))
    }

    override suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String,
        destName: String,
        cityName: String,
        cityd: String?
    ): Result<List<AmapTransitSchemeResult>> {
        if (amapKeys?.privacyConsent?.value != true) return Result.failure(IllegalStateException("未同意高德隐私说明"))
        var sdkFailure: Throwable? = null
        val sdk = amapSdkService
        if (sdk != null && sdkUsable()) {
            val result = sdk.planTransit(
                originLngLat = originLngLat,
                destinationLngLat = destinationLngLat,
                originName = originName,
                destName = destName,
                city = cityName,
                customApiKey = customAmapApiKey,
                cityd = cityd
            )
            currentCoroutineContext().ensureActive()
            if (result.isSuccess) return result
            sdkFailure = result.exceptionOrNull()
        }
        val webKey = effectiveWebServiceKey()
        val web = amapApiClient
        if (webKey.isNotEmpty() && web != null) {
            return web.planTransit(
                originLngLat = originLngLat,
                destinationLngLat = destinationLngLat,
                originName = originName,
                destName = destName,
                city = cityName,
                apiKey = webKey,
                cityd = cityd
            )
        }
        return Result.failure(sdkFailure ?: IllegalStateException("未配置高德 Key"))
    }
}
