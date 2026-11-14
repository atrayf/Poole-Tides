package uk.org.lilliput.pooletides

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Added to the EA gauge's m AOD readings to give metres above Poole chart datum.
 * 1.32 m is the offset the Lilliput app calibrated against WorldTides (April 2026);
 * it matches UKHO low waters better than the nominal 1.40 m.
 */
const val CD_BELOW_ODN = 1.32

private fun get(url: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 20000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000; c.readTimeout = readTimeoutMs
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
        // the EA API is sometimes slow: allow 45 s and try twice
        val url = "$MEASURE?since=$since&_sorted&_limit=5000"
        val body = runCatching { get(url, readTimeoutMs = 45000) }.getOrElse { get(url, readTimeoutMs = 45000) }
        val items = JSONObject(body).getJSONArray("items")
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
    fun fetch(pastDays: Int = 14, forecastDays: Int = 14): Map<Long, Weather> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=50.78&longitude=-1.8425" +
            "&hourly=pressure_msl,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms" +
            "&past_days=$pastDays&forecast_days=$forecastDays&timezone=GMT"
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
 * UKHO ADMIRALTY UK Tidal API, free Discovery tier. Needs a free subscription key from
 * https://developer.admiralty.co.uk . Heights are metres above chart datum.
 */
object Ukho {
    private const val BASE = "https://admiraltyapi.azure-api.net/uktidalapi/api/V1"

    /**
     * Finds the Poole station id. UKHO lists "Poole (Entrance)" (0036) and
     * "POOLE HARBOUR" (0036A, at Poole Quay); prefer the harbour/quay station.
     */
    fun findPooleStation(key: String): Pair<String, String> {
        val features = JSONObject(get("$BASE/Stations", mapOf("Ocp-Apim-Subscription-Key" to key)))
            .getJSONArray("features")
        val poole = (0 until features.length()).map { features.getJSONObject(it).getJSONObject("properties") }
            .filter { it.optString("Name").contains("POOLE", ignoreCase = true) }
        val best = poole.firstOrNull { it.optString("Id") == "0036A" }
            ?: poole.firstOrNull { it.optString("Name").contains("HARBOUR", true) || it.optString("Name").contains("QUAY", true) }
            ?: poole.firstOrNull()
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

/**
 * WorldTides API v3 (paid, credit-based). Continuous heights every 10 min above chart
 * datum at Poole Town Quay. With lat/lon alone WorldTides uses its global model, which
 * smooths away Poole's double high water; stationDistance asks it to use a tide station
 * within that many km instead. https://www.worldtides.info/apidocs
 */
object WorldTides {
    class Result(val heights: List<TidePoint>, val station: String?)

    fun heights(key: String, startEpoch: Long, days: Int = 7): Result {
        val url = "https://www.worldtides.info/api/v3?heights&start=$startEpoch&length=${days * 86400}" +
            "&step=600&lat=50.7139&lon=-1.9876&stationDistance=15&datum=CD&key=" + java.net.URLEncoder.encode(key, "UTF-8")
        val o = JSONObject(get(url))
        if (o.optInt("status", 200) != 200) error(o.optString("error", "WorldTides error"))
        val h = o.getJSONArray("heights")
        val pts = (0 until h.length()).map { val x = h.getJSONObject(it); TidePoint(x.getLong("dt"), x.getDouble("height")) }
        return Result(pts, o.optString("station").takeIf { it.isNotBlank() })
    }
}

/** Removes any API key from an error message before it is shown on screen. */
fun redact(msg: String?, vararg keys: String): String {
    var m = msg ?: "request failed"
    for (k in keys) if (k.length >= 6) m = m.replace(k, "•••")
    return m.lines().first()
}
