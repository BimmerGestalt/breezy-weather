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

package org.breezyweather.daily.adapter.holder

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.breezyweather.R
import org.breezyweather.daily.adapter.DailyWeatherAdapter
import org.breezyweather.daily.adapter.model.ValueIcon

class ValueIconHolder(parent: ViewGroup) : DailyWeatherAdapter.ViewHolder(
    LayoutInflater.from(parent.context).inflate(R.layout.item_weather_daily_valueicon, parent, false)
) {
    private val mIcon: ImageView = itemView.findViewById(R.id.item_weather_daily_valueicon_icon)
    private val mText: TextView = itemView.findViewById(R.id.item_weather_daily_valueicon_text)
    private val mValue: TextView = itemView.findViewById(R.id.item_weather_daily_valueicon_value)

    @SuppressLint("SetTextI18n")
    override fun onBindView(model: DailyWeatherAdapter.ViewModel, position: Int) {
        mText.text = (model as ValueIcon).title
        mValue.text = model.value
        mIcon.setImageResource(model.icon)
    }
}
