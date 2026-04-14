package com.dronealarm.ua.engine

import android.location.Location
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object CapGenerator {
    fun build(
        confidence: Double,
        distanceLabel: String,
        location: Location?
    ): String {
        val id = UUID.randomUUID().toString()
        val sent = isoNow()
        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0

        return """
            <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
              <identifier>$id</identifier>
              <sender>dronealarm.ua</sender>
              <sent>$sent</sent>
              <status>Actual</status>
              <msgType>Alert</msgType>
              <scope>Public</scope>
              <info>
                <category>Security</category>
                <event>Drone Alert</event>
                <urgency>Immediate</urgency>
                <severity>Severe</severity>
                <certainty>Likely</certainty>
                <headline>Ймовірна активність БпЛА</headline>
                <description>Рівень впевненості: ${(confidence * 100).toInt()}%, дистанція: $distanceLabel</description>
                <area>
                  <areaDesc>Остання відома позиція сенсора</areaDesc>
                  <circle>$lat,$lon 0.5</circle>
                </area>
              </info>
            </alert>
        """.trimIndent()
    }

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
