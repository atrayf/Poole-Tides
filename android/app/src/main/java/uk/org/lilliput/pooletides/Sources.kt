package uk.org.lilliput.pooletides

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Poole Quay chart datum is 1.40 m below Ordnance Datum Newlyn. */
const val CD_BELOW_ODN = 1.40

private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000; c.readTimeout = 20000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    try {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} from ${URL(url).host}")
        return c.inputStream.bufferedReader().readText()
    } finally { c.disconnect() }
}

/** Environment Agency gauge 44232 (POOLE RNLI, by Poole Quay), 15-minute, m AOD. */
object PooleGauge {
    private const val MEASURE =
        "https://environment.data.gov.uk/flood-monitoring/id/measures/44232-level-1-i-15_min-mAOD/readings"

    fun fetch(sinceEpoch: Long): List<TidePoint> {
        val since = Instant.ofEpochSecond(sinceEpoch).toString()
        val items = JSONObject(get("$MEASURE?since=$since&_sorted&_limit=5000")).getJSONArray("items")
        return (0 until items.length()).mapNotNull {
            val o = items.getJSONObject(it)
            val v = o.optDouble("value", Double.NaN)
            if (v.isNaN()) null
            else TidePoint(Instant.parse(o.getString("dateTime")).epochSecond, v + CD_BELOW_ODN)
        }.sortedBy { it.t }
    }
}

/**
 * Hourly MSL pressure and 10 m wind at Bournemouth Airport from Open-Meteo
 * (free, no key): recent past plus 7-day forecast, used to drive the surge model.
 */
object BournemouthWeather {
    fun fetch(): Map<Long, Weather> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=50.78&longitude=-1.8425" +
            "&hourly=pressure_msl,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms" +
            "&past_days=3&forecast_days=7&timezone=GMT"
        val h = JSONObject(get(url)).getJSONObject("hourly")
        val t = h.getJSONArray("time"); val p = h.getJSONArray("pressure_msl")
        val s = h.getJSONArray("wind_speed_10m"); val d = h.getJSONArray("wind_direction_10m")
        val out = HashMap<Long, Weather>()
        for (i in 0 until t.length()) {
            if (p.isNull(i) || s.isNull(i) || d.isNull(i)) continue
            val e = LocalDateTime.parse(t.getString(i)).toEpochSecond(ZoneOffset.UTC)
            out[e] = Weather(e, p.getDouble(i), s.getDouble(i), d.getDouble(i))
        }
        return out
    }
}

/**
 * UKHO ADMIRALTY UK Tidal API, free Discovery tier (the same UKHO data that the
 * Absolute Tides app licenses). Needs a free subscription key from
 * https://admiraltyapi.portal.azure-api.net/ . Heights are metres above chart datum.
 */
object Ukho {
    private const val BASE = "https://admiraltyapi.azure-api.net/uktidalapi/api/V1"

    /** Finds the Poole station id, preferring Poole Town Quay. */
    fun findPooleStation(key: String): Pair<String, String> {
        val features = JSONObject(get("$BASE/Stations", mapOf("Ocp-Apim-Subscription-Key" to key)))
            .getJSONArray("features")
        val poole = (0 until features.length()).map { features.getJSONObject(it).getJSONObject("properties") }
            .filter { it.optString("Name").contains("POOLE", ignoreCase = true) }
        val best = poole.firstOrNull { it.optString("Name").contains("QUAY", true) } ?: poole.firstOrNull()
            ?: error("No Poole station in UKHO list")
        return best.getString("Id") to best.getString("Name")
    }

    fun events(key: String, stationId: String): List<TidePoint> {
        val arr = JSONArray(get("$BASE/Stations/$stationId/TidalEvents?duration=7",
            mapOf("Ocp-Apim-Subscription-Key" to key)))
        return (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            if (!o.has("Height") || o.isNull("Height")) return@mapNotNull null
            val dt = o.getString("DateTime").let { s -> if (s.endsWith("Z")) s.dropLast(1) else s }
            TidePoint(LocalDateTime.parse(dt).toEpochSecond(ZoneOffset.UTC), o.getDouble("Height"))
        }
    }
}
