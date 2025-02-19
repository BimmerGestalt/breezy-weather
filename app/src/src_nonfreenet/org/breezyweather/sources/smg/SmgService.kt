/**
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.smg

import android.content.Context
import android.graphics.Color
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceContinent
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.wrappers.SecondaryWeatherWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.breezyweather.common.exceptions.SecondaryWeatherException
import org.breezyweather.common.extensions.code
import org.breezyweather.common.extensions.currentLocale
import org.breezyweather.common.source.HttpSource
import org.breezyweather.common.source.MainWeatherSource
import org.breezyweather.common.source.SecondaryWeatherSource
import org.breezyweather.sources.smg.json.SmgAirQualityResult
import org.breezyweather.sources.smg.json.SmgAstroResult
import org.breezyweather.sources.smg.json.SmgBulletinResult
import org.breezyweather.sources.smg.json.SmgCurrentResult
import org.breezyweather.sources.smg.json.SmgForecastResult
import org.breezyweather.sources.smg.json.SmgUvResult
import org.breezyweather.sources.smg.json.SmgWarningResult
import retrofit2.Retrofit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Named
import kotlin.text.startsWith

class SmgService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") client: Retrofit.Builder,
) : HttpSource(), MainWeatherSource, SecondaryWeatherSource {

    override val id = "smg"

    // Even in its English bulletins, SMG refers to itself as "SMG" rather than
    // "Macao Meteorological and Geophysical Bureau". Keep the Portuguese name
    // for use in the source list. The English name can be used in attributions.
    override val name by lazy {
        if (context.currentLocale.code.startsWith("zh")) {
            "地球物理氣象局"
        } else {
            "Serviços Meteorológicos e Geofísicos (SMG)"
        }
    }
    override val continent = SourceContinent.ASIA
    override val privacyPolicyUrl by lazy {
        with(context.currentLocale.code) {
            when {
                startsWith("zh") -> "https://www.smg.gov.mo/zh/subpage/21/page/21"
                startsWith("pt") -> "https://www.smg.gov.mo/pt/subpage/21/page/21"
                else -> "https://www.smg.gov.mo/en/subpage/21/page/21"
            }
        }
    }

    override val color = Color.rgb(1, 173, 159)
    override val weatherAttribution by lazy {
        with(context.currentLocale.code) {
            when {
                startsWith("zh") -> "地球物理氣象局"
                startsWith("pt") -> "Direcção dos Serviços Meteorológicos e Geofísicos"
                else -> "Macao Meteorological and Geophysical Bureau"
            }
        }
    }

    private val mApi by lazy {
        client
            .baseUrl(SMG_BASE_URL)
            .build()
            .create(SmgApi::class.java)
    }

    private val mCmsApi by lazy {
        client
            .baseUrl(SMG_CMS_URL)
            .build()
            .create(SmgCmsApi::class.java)
    }

    override val supportedFeaturesInMain = listOf(
        SourceFeature.FEATURE_CURRENT,
        SourceFeature.FEATURE_ALERT,
        SourceFeature.FEATURE_AIR_QUALITY,
        SourceFeature.FEATURE_NORMALS
    )

    override fun isFeatureSupportedInMainForLocation(
        location: Location,
        feature: SourceFeature?,
    ): Boolean {
        return location.countryCode.equals("MO", ignoreCase = true)
    }

    override fun requestWeather(
        context: Context,
        location: Location,
        ignoreFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        val lang = with(context.currentLocale.code) {
            when {
                startsWith("zh") -> "c"
                startsWith("pt") -> "p"
                else -> "e"
            }
        }

        val failedFeatures = mutableListOf<SourceFeature>()
        val daily = mApi.getDaily()
        val hourly = mApi.getHourly()

        // ASTRO
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        formatter.timeZone = TimeZone.getTimeZone("Asia/Macau")
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Macau"))
        var body = """{"date":"${formatter.format(now.time)}"}"""
        val astro = mApi.getAstro(
            body.toRequestBody("application/json".toMediaTypeOrNull())
        ).onErrorResumeNext {
            /*if (BreezyWeather.instance.debugMode) {
                failedFeatures.add(SourceFeature.FEATURE_OTHER)
            }*/
            Observable.just(SmgAstroResult())
        }

        // CURRENT
        val current = if (!ignoreFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getCurrent().onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(SmgCurrentResult())
            }
        } else {
            Observable.just(SmgCurrentResult())
        }
        val bulletin = if (!ignoreFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getBulletin(
                lang = lang
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(SmgBulletinResult())
            }
        } else {
            Observable.just(SmgBulletinResult())
        }
        val uv = if (!ignoreFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getUVIndex().onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(SmgUvResult())
            }
        } else {
            Observable.just(SmgUvResult())
        }

        // ALERT
        val alerts = MutableList(SMG_ALERT_TYPES.size) {
            if (!ignoreFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                mApi.getWarning(SMG_ALERT_TYPES[it], lang).onErrorResumeNext {
                    failedFeatures.add(SourceFeature.FEATURE_ALERT)
                    Observable.just(SmgWarningResult())
                }
            } else {
                Observable.just(SmgWarningResult())
            }
        }

        val warnings = Observable.zip(alerts[0], alerts[1], alerts[2], alerts[3], alerts[4], alerts[5]) {
                typhoonResult: SmgWarningResult,
                rainstormResult: SmgWarningResult,
                monsoonResult: SmgWarningResult,
                thunderstormResult: SmgWarningResult,
                stormsurgeResult: SmgWarningResult,
                tsunamiResult: SmgWarningResult,
            ->
            listOf(
                typhoonResult,
                rainstormResult,
                monsoonResult,
                thunderstormResult,
                stormsurgeResult,
                tsunamiResult
            )
        }

        // AIR QUALITY
        val airQuality = if (!ignoreFeatures.contains(SourceFeature.FEATURE_AIR_QUALITY)) {
            mCmsApi.getAirQuality(Calendar.getInstance().timeInMillis).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_AIR_QUALITY)
                Observable.just(SmgAirQualityResult())
            }
        } else {
            Observable.just(SmgAirQualityResult())
        }

        return Observable.zip(current, daily, hourly, bulletin, uv, warnings, airQuality, astro) {
                currentResult: SmgCurrentResult,
                dailyResult: SmgForecastResult,
                hourlyResult: SmgForecastResult,
                bulletinResult: SmgBulletinResult,
                uvResult: SmgUvResult,
                warningsResult: List<SmgWarningResult>,
                airQualityResult: SmgAirQualityResult,
                astroResult: SmgAstroResult,
            ->
            convert(
                context = context,
                currentResult = currentResult,
                dailyResult = dailyResult,
                hourlyResult = hourlyResult,
                astroResult = astroResult,
                bulletinResult = bulletinResult,
                uvResult = uvResult,
                warningsResult = warningsResult,
                airQualityResult = airQualityResult,
                includeNormals = !ignoreFeatures.contains(SourceFeature.FEATURE_NORMALS),
                failedFeatures = failedFeatures
            )
        }
    }

    // SECONDARY WEATHER SOURCE
    override val supportedFeaturesInSecondary = listOf(
        SourceFeature.FEATURE_CURRENT,
        SourceFeature.FEATURE_ALERT,
        SourceFeature.FEATURE_AIR_QUALITY,
        SourceFeature.FEATURE_NORMALS
    )
    override fun isFeatureSupportedInSecondaryForLocation(
        location: Location,
        feature: SourceFeature,
    ): Boolean {
        return isFeatureSupportedInMainForLocation(location, feature)
    }
    override val currentAttribution = weatherAttribution
    override val airQualityAttribution = weatherAttribution
    override val pollenAttribution = null
    override val minutelyAttribution = null
    override val alertAttribution = weatherAttribution
    override val normalsAttribution = weatherAttribution

    override fun requestSecondaryWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<SecondaryWeatherWrapper> {
        if (!isFeatureSupportedInSecondaryForLocation(location, SourceFeature.FEATURE_CURRENT) ||
            !isFeatureSupportedInSecondaryForLocation(location, SourceFeature.FEATURE_ALERT) ||
            !isFeatureSupportedInSecondaryForLocation(location, SourceFeature.FEATURE_AIR_QUALITY) ||
            !isFeatureSupportedInSecondaryForLocation(location, SourceFeature.FEATURE_NORMALS)
        ) {
            // TODO: return Observable.error(UnsupportedFeatureForLocationException())
            return Observable.error(SecondaryWeatherException())
        }
        val lang = with(context.currentLocale.code) {
            when {
                startsWith("zh") -> "c"
                startsWith("pt") -> "p"
                else -> "e"
            }
        }

        val failedFeatures = mutableListOf<SourceFeature>()
        // CURRENT
        val current = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getCurrent().onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(SmgCurrentResult())
            }
        } else {
            Observable.just(SmgCurrentResult())
        }
        val bulletin = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getBulletin(
                lang = lang
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(SmgBulletinResult())
            }
        } else {
            Observable.just(SmgBulletinResult())
        }
        val uv = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getUVIndex().onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(SmgUvResult())
            }
        } else {
            Observable.just(SmgUvResult())
        }

        // ALERT
        val alerts = MutableList(SMG_ALERT_TYPES.size) {
            if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                mApi.getWarning(SMG_ALERT_TYPES[it], lang).onErrorResumeNext {
                    failedFeatures.add(SourceFeature.FEATURE_ALERT)
                    Observable.just(SmgWarningResult())
                }
            } else {
                Observable.just(SmgWarningResult())
            }
        }

        val warnings = Observable.zip(alerts[0], alerts[1], alerts[2], alerts[3], alerts[4], alerts[5]) {
                typhoonResult: SmgWarningResult,
                rainstormResult: SmgWarningResult,
                monsoonResult: SmgWarningResult,
                thunderstormResult: SmgWarningResult,
                stormsurgeResult: SmgWarningResult,
                tsunamiResult: SmgWarningResult,
            ->
            listOf(
                typhoonResult,
                rainstormResult,
                monsoonResult,
                thunderstormResult,
                stormsurgeResult,
                tsunamiResult
            )
        }

        // AIR QUALITY
        val airQuality = if (requestedFeatures.contains(SourceFeature.FEATURE_AIR_QUALITY)) {
            mCmsApi.getAirQuality(Calendar.getInstance().timeInMillis).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_AIR_QUALITY)
                Observable.just(SmgAirQualityResult())
            }
        } else {
            Observable.just(SmgAirQualityResult())
        }

        return Observable.zip(current, bulletin, uv, warnings, airQuality) {
                currentResult: SmgCurrentResult,
                bulletinResult: SmgBulletinResult,
                uvResult: SmgUvResult,
                warningsResult: List<SmgWarningResult>,
                airQualityResult: SmgAirQualityResult,
            ->
            convertSecondary(
                context = context,
                currentResult = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
                    currentResult
                } else {
                    null
                },
                bulletinResult = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
                    bulletinResult
                } else {
                    null
                },
                uvResult = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
                    uvResult
                } else {
                    null
                },
                warningsResult = if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    warningsResult
                } else {
                    null
                },
                airQualityResult = if (requestedFeatures.contains(SourceFeature.FEATURE_AIR_QUALITY)) {
                    airQualityResult
                } else {
                    null
                },
                includeNormals = requestedFeatures.contains(SourceFeature.FEATURE_NORMALS),
                failedFeatures = failedFeatures
            )
        }
    }

    companion object {
        private const val SMG_BASE_URL = "https://new-api.smg.gov.mo/"
        private const val SMG_CMS_URL = "https://cms.smg.gov.mo/"
        private val SMG_ALERT_TYPES = listOf("typhoon", "rainstorm", "monsoon", "thunderstorm", "stormsurge", "tsunami")
    }
}
