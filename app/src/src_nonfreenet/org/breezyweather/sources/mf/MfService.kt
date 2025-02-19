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

package org.breezyweather.sources.mf

import android.content.Context
import android.graphics.Color
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceContinent
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.wrappers.SecondaryWeatherWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.common.exceptions.ApiKeyMissingException
import org.breezyweather.common.extensions.code
import org.breezyweather.common.extensions.currentLocale
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.common.source.ConfigurableSource
import org.breezyweather.common.source.HttpSource
import org.breezyweather.common.source.MainWeatherSource
import org.breezyweather.common.source.ReverseGeocodingSource
import org.breezyweather.common.source.SecondaryWeatherSource
import org.breezyweather.settings.SourceConfigStore
import org.breezyweather.sources.mf.json.MfCurrentResult
import org.breezyweather.sources.mf.json.MfEphemerisResult
import org.breezyweather.sources.mf.json.MfForecastResult
import org.breezyweather.sources.mf.json.MfNormalsResult
import org.breezyweather.sources.mf.json.MfRainResult
import org.breezyweather.sources.mf.json.MfWarningsResult
import retrofit2.Retrofit
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named

/**
 * Mf weather service.
 */
class MfService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") client: Retrofit.Builder,
) : HttpSource(), MainWeatherSource, SecondaryWeatherSource, ReverseGeocodingSource, ConfigurableSource {

    override val id = "mf"
    override val name = "Météo-France"
    override val continent = SourceContinent.EUROPE
    override val privacyPolicyUrl = "https://meteofrance.com/application-meteo-france-politique-de-confidentialite"

    override val color = Color.rgb(0, 87, 147)
    override val weatherAttribution = "Météo-France (Etalab)"

    private val mApi by lazy {
        client
            .baseUrl(MF_BASE_URL)
            .build()
            .create(MfApi::class.java)
    }

    override val supportedFeaturesInMain = listOf(
        SourceFeature.FEATURE_CURRENT,
        SourceFeature.FEATURE_MINUTELY,
        SourceFeature.FEATURE_ALERT,
        SourceFeature.FEATURE_NORMALS
    )

    override fun requestWeather(
        context: Context,
        location: Location,
        ignoreFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        val languageCode = context.currentLocale.code
        val token = getToken()
        val failedFeatures = mutableListOf<SourceFeature>()
        val current = if (!ignoreFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getCurrent(
                USER_AGENT,
                location.latitude,
                location.longitude,
                languageCode,
                "iso",
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(MfCurrentResult())
            }
        } else {
            Observable.just(MfCurrentResult())
        }
        val forecast = mApi.getForecast(
            USER_AGENT,
            location.latitude,
            location.longitude,
            "iso",
            token
        )
        val ephemeris = mApi.getEphemeris(
            USER_AGENT,
            location.latitude,
            location.longitude,
            "en", // English required to convert moon phase
            "iso",
            token
        ).onErrorResumeNext {
            /*if (BreezyWeather.instance.debugMode) {
                failedFeatures.add(SourceFeature.FEATURE_OTHER)
            }*/
            Observable.just(MfEphemerisResult())
        }
        val rain = if (!ignoreFeatures.contains(SourceFeature.FEATURE_MINUTELY)) {
            mApi.getRain(
                USER_AGENT,
                location.latitude,
                location.longitude,
                languageCode,
                "iso",
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_MINUTELY)
                Observable.just(MfRainResult())
            }
        } else {
            Observable.just(MfRainResult())
        }
        val warnings = if (!ignoreFeatures.contains(SourceFeature.FEATURE_ALERT) &&
            !location.countryCode.isNullOrEmpty() &&
            location.countryCode.equals("FR", ignoreCase = true) &&
            !location.admin2Code.isNullOrEmpty()
        ) {
            mApi.getWarnings(
                USER_AGENT,
                location.admin2Code!!,
                "iso",
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_ALERT)
                Observable.just(MfWarningsResult())
            }
        } else {
            Observable.just(MfWarningsResult())
        }

        // TODO: Only call once a month, unless it’s current position
        val normals = if (!ignoreFeatures.contains(SourceFeature.FEATURE_NORMALS)) {
            mApi.getNormals(
                USER_AGENT,
                location.latitude,
                location.longitude,
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_NORMALS)
                Observable.just(MfNormalsResult())
            }
        } else {
            Observable.just(MfNormalsResult())
        }

        return Observable.zip(current, forecast, ephemeris, rain, warnings, normals) {
                mfCurrentResult: MfCurrentResult,
                mfForecastResult: MfForecastResult,
                mfEphemerisResult: MfEphemerisResult,
                mfRainResult: MfRainResult,
                mfWarningResults: MfWarningsResult,
                mfNormalsResult: MfNormalsResult,
            ->
            convert(
                location,
                mfCurrentResult,
                mfForecastResult,
                mfEphemerisResult,
                mfRainResult,
                mfWarningResults,
                mfNormalsResult,
                failedFeatures
            )
        }
    }

    // SECONDARY WEATHER SOURCE
    override val supportedFeaturesInSecondary = listOf(
        SourceFeature.FEATURE_CURRENT,
        SourceFeature.FEATURE_MINUTELY,
        SourceFeature.FEATURE_ALERT,
        SourceFeature.FEATURE_NORMALS
    )
    override fun isFeatureSupportedInSecondaryForLocation(
        location: Location,
        feature: SourceFeature,
    ): Boolean {
        return isConfigured &&
            (
                feature == SourceFeature.FEATURE_CURRENT &&
                    !location.countryCode.isNullOrEmpty() &&
                    location.countryCode.equals("FR", ignoreCase = true)
                ) ||
            (
                feature == SourceFeature.FEATURE_MINUTELY &&
                    !location.countryCode.isNullOrEmpty() &&
                    location.countryCode.equals("FR", ignoreCase = true)
                ) ||
            (
                feature == SourceFeature.FEATURE_ALERT &&
                    !location.countryCode.isNullOrEmpty() &&
                    location.countryCode.equals("FR", ignoreCase = true) &&
                    !location.admin2Code.isNullOrEmpty()
                ) ||
            (
                // Technically, works anywhere but as a France-focused source, we don’t want the whole
                // world to use this source, as currently the only alternative is AccuWeather
                feature == SourceFeature.FEATURE_NORMALS &&
                    !location.countryCode.isNullOrEmpty() &&
                    location.countryCode.equals("FR", ignoreCase = true)
                )
    }
    override val currentAttribution = weatherAttribution
    override val airQualityAttribution = null
    override val pollenAttribution = null
    override val minutelyAttribution = weatherAttribution
    override val alertAttribution = weatherAttribution
    override val normalsAttribution = weatherAttribution

    override fun requestSecondaryWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<SecondaryWeatherWrapper> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        val languageCode = context.currentLocale.code
        val token = getToken()

        val failedFeatures = mutableListOf<SourceFeature>()
        val current = if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
            mApi.getCurrent(
                USER_AGENT,
                location.latitude,
                location.longitude,
                languageCode,
                "iso",
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_CURRENT)
                Observable.just(MfCurrentResult())
            }
        } else {
            Observable.just(MfCurrentResult())
        }
        val rain = if (requestedFeatures.contains(SourceFeature.FEATURE_MINUTELY)) {
            mApi.getRain(
                USER_AGENT,
                location.latitude,
                location.longitude,
                languageCode,
                "iso",
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_MINUTELY)
                Observable.just(MfRainResult())
            }
        } else {
            Observable.just(MfRainResult())
        }

        val warnings = if (
            requestedFeatures.contains(SourceFeature.FEATURE_ALERT) &&
            !location.countryCode.isNullOrEmpty() &&
            location.countryCode.equals("FR", ignoreCase = true) &&
            !location.admin2Code.isNullOrEmpty()
        ) {
            mApi.getWarnings(
                USER_AGENT,
                location.admin2Code!!,
                "iso",
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_ALERT)
                Observable.just(MfWarningsResult())
            }
        } else {
            Observable.just(MfWarningsResult())
        }

        val normals = if (requestedFeatures.contains(SourceFeature.FEATURE_NORMALS)) {
            mApi.getNormals(
                USER_AGENT,
                location.latitude,
                location.longitude,
                token
            ).onErrorResumeNext {
                failedFeatures.add(SourceFeature.FEATURE_NORMALS)
                Observable.just(MfNormalsResult())
            }
        } else {
            Observable.just(MfNormalsResult())
        }

        return Observable.zip(current, rain, warnings, normals) {
                mfCurrentResult: MfCurrentResult,
                mfRainResult: MfRainResult,
                mfWarningResults: MfWarningsResult,
                mfNormalsResult: MfNormalsResult,
            ->
            convertSecondary(
                location,
                if (requestedFeatures.contains(SourceFeature.FEATURE_CURRENT)) {
                    mfCurrentResult
                } else {
                    null
                },
                if (requestedFeatures.contains(SourceFeature.FEATURE_MINUTELY)) {
                    mfRainResult
                } else {
                    null
                },
                if (requestedFeatures.contains(SourceFeature.FEATURE_ALERT)) {
                    mfWarningResults
                } else {
                    null
                },
                if (requestedFeatures.contains(SourceFeature.FEATURE_NORMALS)) {
                    mfNormalsResult
                } else {
                    null
                },
                failedFeatures
            )
        }
    }

    override fun requestReverseGeocodingLocation(
        context: Context,
        location: Location,
    ): Observable<List<Location>> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        return mApi.getForecast(
            USER_AGENT,
            location.latitude,
            location.longitude,
            "iso",
            getToken()
        ).map {
            listOf(convert(location, it))
        }
    }

    // CONFIG
    private val config = SourceConfigStore(context, id)
    private var wsftKey: String
        set(value) {
            config.edit().putString("wsft_key", value).apply()
        }
        get() = config.getString("wsft_key", null) ?: ""

    private fun getWsftKeyOrDefault(): String {
        return wsftKey.ifEmpty { BuildConfig.MF_WSFT_KEY }
    }

    override val isConfigured
        get() = getToken().isNotEmpty()

    override val isRestricted = false

    private fun getToken(): String {
        return if (getWsftKeyOrDefault() != BuildConfig.MF_WSFT_KEY) {
            // If default key was changed, we want to use it
            getWsftKeyOrDefault()
        } else {
            // Otherwise, we try first a JWT key, otherwise fallback on regular API key
            try {
                Jwts.builder().apply {
                    header().add("typ", "JWT")
                    claims().empty().add("class", "mobile")
                    issuedAt(Date())
                    id(UUID.randomUUID().toString())
                    signWith(
                        Keys.hmacShaKeyFor(
                            BuildConfig.MF_WSFT_JWT_KEY.toByteArray(
                                StandardCharsets.UTF_8
                            )
                        ),
                        Jwts.SIG.HS256
                    )
                }.compact()
            } catch (ignored: Exception) {
                BuildConfig.MF_WSFT_KEY
            }
        }
    }

    override fun getPreferences(context: Context): List<Preference> {
        return listOf(
            EditTextPreference(
                titleId = R.string.settings_weather_source_mf_api_key,
                summary = { c, content ->
                    content.ifEmpty {
                        c.getString(R.string.settings_source_default_value)
                    }
                },
                content = wsftKey,
                onValueChanged = {
                    wsftKey = it
                }
            )
        )
    }

    companion object {
        private const val MF_BASE_URL = "https://webservice.meteofrance.com/"
        private const val USER_AGENT = "okhttp/4.9.2"
    }
}
