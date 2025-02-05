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

package org.breezyweather.sources.bmkg

import android.content.Context
import android.graphics.Color
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceContinent
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.wrappers.SecondaryWeatherWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.common.exceptions.ApiKeyMissingException
import org.breezyweather.common.exceptions.SecondaryWeatherException
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.common.source.ConfigurableSource
import org.breezyweather.common.source.HttpSource
import org.breezyweather.common.source.MainWeatherSource
import org.breezyweather.common.source.ReverseGeocodingSource
import org.breezyweather.common.source.SecondaryWeatherSource
import org.breezyweather.settings.SourceConfigStore
import org.breezyweather.sources.bmkg.json.BmkgCurrentResult
import org.breezyweather.sources.bmkg.json.BmkgForecastResult
import org.breezyweather.sources.bmkg.json.BmkgIbfResult
import org.breezyweather.sources.bmkg.json.BmkgPm25Result
import org.breezyweather.sources.bmkg.json.BmkgWarningResult
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Named
import kotlin.text.ifEmpty

class BmkgService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") client: Retrofit.Builder,
) : HttpSource(), MainWeatherSource, SecondaryWeatherSource, ReverseGeocodingSource, ConfigurableSource {

    override val id = "bmkg"
    override val name = "BMKG"
    override val continent = SourceContinent.ASIA
    override val privacyPolicyUrl = ""

    override val color = Color.rgb(0, 153, 0)
    override val weatherAttribution = "Badan Meteorologi, Klimatologi, dan Geofisika"

    private val mApi by lazy {
        client
            .baseUrl(BMKG_BASE_URL)
            .build()
            .create(BmkgApi::class.java)
    }

    private val mAppApi by lazy {
        client
            .baseUrl(BMKG_APP_BASE_URL)
            .build()
            .create(BmkgAppApi::class.java)
    }

    override val supportedFeaturesInMain = listOf(
        SourceFeature.FEATURE_CURRENT,
        SourceFeature.FEATURE_ALERT,
        SourceFeature.FEATURE_AIR_QUALITY
    )

    override fun isFeatureSupportedInMainForLocation(
        location: Location,
        feature: SourceFeature?,
    ): Boolean {
        return location.countryCode.equals("ID", ignoreCase = true)
    }

    override fun requestWeather(
        context: Context,
        location: Location,
        ignoreFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        // API Key is needed for warnings, but not for current/forecast.
        // Only throw exception if warnings are needed.
        if (!isConfigured && !ignoreFeatures.contains(SourceFeature.FEATURE_ALERT)) {
            return Observable.error(ApiKeyMissingException())
        }
        val apiKey = getApiKeyOrDefault()

        val forecast = mApi.getForecast(
            lat = location.latitude,
            lon = location.longitude
        )

        val failedFeatures = mutableListOf<SourceFeature>()
        val current = if (!ignoreFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getCurrent(
                lat = location.latitude,
                lon = location.longitude
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(BmkgCurrentResult())
            }
        } else {
            Observable.just(BmkgCurrentResult())
        }

        val warning = if (!ignoreFeatures.contains(SourceFeature.FEATURE_ALERT)) {
            mApi.getWarning(
                apiKey = apiKey,
                lat = location.latitude,
                lon = location.longitude
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_ALERT)
                Observable.just(BmkgWarningResult())
            }
        } else {
            Observable.just(BmkgWarningResult())
        }

        // Impact based forecasts provide early warnings of heavy rain up to 3 days
        val ibf = mutableListOf<Observable<BmkgIbfResult>>()
        for (day in 1..3) {
            ibf.add(
                if (!ignoreFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    mApi.getIbf(
                        apiKey = apiKey,
                        lat = location.latitude,
                        lon = location.longitude,
                        day = day
                    ).onErrorResumeNext {
                        failedFeatures.add(SourceFeature.FEATURE_ALERT)
                        Observable.just(BmkgIbfResult())
                    }
                } else {
                    Observable.just(BmkgIbfResult())
                }
            )
        }

        val pm25 = if (!ignoreFeatures.contains(SourceFeature.FEATURE_AIR_QUALITY)) {
            mAppApi.getPm25().onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_AIR_QUALITY)
                Observable.just(emptyList())
            }
        } else {
            Observable.just(emptyList())
        }

        return Observable.zip(current, forecast, warning, ibf[0], ibf[1], ibf[2], pm25) {
                currentResult: BmkgCurrentResult,
                forecastResult: BmkgForecastResult,
                warningResult: BmkgWarningResult,
                ibf1Result: BmkgIbfResult,
                ibf2Result: BmkgIbfResult,
                ibf3Result: BmkgIbfResult,
                pm25Result: List<BmkgPm25Result>,
            ->
            convert(
                context,
                location,
                currentResult,
                forecastResult,
                warningResult,
                ibf1Result,
                ibf2Result,
                ibf3Result,
                pm25Result,
                failedFeatures
            )
        }
    }

    // SECONDARY WEATHER SOURCE
    override val supportedFeaturesInSecondary = listOf(
        SourceFeature.FEATURE_CURRENT,
        SourceFeature.FEATURE_ALERT,
        SourceFeature.FEATURE_AIR_QUALITY
    )

    override fun isFeatureSupportedInSecondaryForLocation(
        location: Location,
        feature: SourceFeature,
    ): Boolean {
        return isFeatureSupportedInMainForLocation(location, feature)
    }
    override val currentAttribution = weatherAttribution
    override val airQualityAttribution = null
    override val pollenAttribution = null
    override val minutelyAttribution = null
    override val alertAttribution = weatherAttribution
    override val normalsAttribution = null

    override fun requestSecondaryWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<SecondaryWeatherWrapper> {
        if (!isFeatureSupportedInSecondaryForLocation(location, SourceFeature.FEATURE_ALERT) ||
            !isFeatureSupportedInSecondaryForLocation(location, SourceFeature.FEATURE_CURRENT) ||
            !isFeatureSupportedInSecondaryForLocation(location, SourceFeature.FEATURE_AIR_QUALITY)
        ) {
            // TODO: return Observable.error(UnsupportedFeatureForLocationException())
            return Observable.error(SecondaryWeatherException())
        }

        // API Key is needed for warnings, but not for current/forecast.
        // Only throw exception if warnings are needed.
        if (!isConfigured && requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
            return Observable.error(ApiKeyMissingException())
        }
        val apiKey = getApiKeyOrDefault()

        val failedFeatures = mutableListOf<SourceFeature>()
        val current = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getCurrent(
                lat = location.latitude,
                lon = location.longitude
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(BmkgCurrentResult())
            }
        } else {
            Observable.just(BmkgCurrentResult())
        }

        val warning = if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
            mApi.getWarning(
                apiKey = apiKey,
                lat = location.latitude,
                lon = location.longitude
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_ALERT)
                Observable.just(BmkgWarningResult())
            }
        } else {
            Observable.just(BmkgWarningResult())
        }

        // Impact based forecasts provide early warnings of heavy rain up to 3 days
        val ibf = mutableListOf<Observable<BmkgIbfResult>>()
        for (day in 1..3) {
            ibf.add(
                if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    mApi.getIbf(
                        apiKey = apiKey,
                        lat = location.latitude,
                        lon = location.longitude,
                        day = day
                    ).onErrorResumeNext {
                        failedFeatures.add(SourceFeature.FEATURE_ALERT)
                        Observable.just(BmkgIbfResult())
                    }
                } else {
                    Observable.just(BmkgIbfResult())
                }
            )
        }

        val pm25 = if (requestedFeatures.contains(SourceFeature.FEATURE_AIR_QUALITY)) {
            mAppApi.getPm25().onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_AIR_QUALITY)
                Observable.just(emptyList())
            }
        } else {
            Observable.just(emptyList())
        }

        return Observable.zip(current, warning, ibf[0], ibf[1], ibf[2], pm25) {
                currentResult: BmkgCurrentResult,
                warningResult: BmkgWarningResult,
                ibf1Result: BmkgIbfResult,
                ibf2Result: BmkgIbfResult,
                ibf3Result: BmkgIbfResult,
                pm25Result: List<BmkgPm25Result>,
            ->
            convertSecondary(
                context = context,
                location = location,
                currentResult = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
                    currentResult
                } else {
                    null
                },
                warningResult = if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    warningResult
                } else {
                    null
                },
                ibf1Result = if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    ibf1Result
                } else {
                    null
                },
                ibf2Result = if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    ibf2Result
                } else {
                    null
                },
                ibf3Result = if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    ibf3Result
                } else {
                    null
                },
                pm25Result = if (requestedFeatures.contains(SourceFeature.FEATURE_AIR_QUALITY)) {
                    pm25Result
                } else {
                    null
                },
                failedFeatures = failedFeatures
            )
        }
    }

    override fun requestReverseGeocodingLocation(
        context: Context,
        location: Location,
    ): Observable<List<Location>> {
        val locationList = mutableListOf<Location>()
        return mApi.getLocation(
            lat = location.latitude,
            lon = location.longitude
        ).map {
            locationList.add(convert(location, it))
            locationList
        }
    }

    // CONFIG
    private val config = SourceConfigStore(context, id)
    private var apikey: String
        set(value) {
            config.edit().putString("apikey", value).apply()
        }
        get() = config.getString("apikey", null) ?: ""

    private fun getApiKeyOrDefault(): String {
        return apikey.ifEmpty { BuildConfig.BMKG_KEY }
    }

    override val isConfigured
        get() = getApiKeyOrDefault().isNotEmpty()

    override val isRestricted
        get() = apikey.isEmpty()

    override fun getPreferences(context: Context): List<Preference> {
        return listOf(
            EditTextPreference(
                titleId = R.string.settings_weather_source_bmkg_api_key,
                summary = { c, content ->
                    content.ifEmpty {
                        c.getString(R.string.settings_source_default_value)
                    }
                },
                content = apikey,
                onValueChanged = {
                    apikey = it
                }
            )
        )
    }

    companion object {
        private const val BMKG_BASE_URL = "https://cuaca.bmkg.go.id/"
        private const val BMKG_APP_BASE_URL = "https://api-apps.bmkg.go.id/"
    }
}
