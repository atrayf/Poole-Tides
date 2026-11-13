package uk.org.lilliput.pooletides

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/** A height (metres above Poole chart datum) at a time (epoch seconds, UTC). */
data class TidePoint(val t: Long, val h: Double)

/** One hour of Bournemouth weather. */
data class Weather(val t: Long, val mslpHpa: Double, val windMs: Double, val windFromDeg: Double)

/**
 * Astronomical tide from the harmonic constants fitted to the Poole Quay gauge,
 * stored as a precomputed 10-minute table (centimetres above chart datum).
 * Header: start epoch (int64), step seconds (int64), count (int64), then int16 values.
 */
class AstroTable(bytes: ByteArray) {
    private val start: Long
    private val step: Long
    private val values: ShortArray

    init {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        start = bb.long
        step = bb.long
        val n = bb.long.toInt()
        values = ShortArray(n) { bb.short }
    }

    val endEpoch: Long get() = start + step * (values.size - 1)

    fun heightAt(t: Long): Double? {
        if (t < start || t > endEpoch) return null
        val x = (t - start).toDouble() / step
        val i = x.toInt().coerceAtMost(values.size - 2)
        val f = x - i
        return (values[i] * (1 - f) + values[i + 1] * f) / 100.0
    }
}

/**
 * Meteorological surge: linear model on pressure anomaly and quadratic wind stress
 * at several lags, each a trailing 6-hour mean, matching fit_model.py exactly.
 */
class SurgeModel(json: String) {
    private val lags: List<Int>
    private val smoothing: Int
    private val coef: Map<String, Double>
    private val intercept: Double
    private val pRef: Double

    init {
        val o = JSONObject(json)
        val l = o.getJSONArray("lags_h"); lags = (0 until l.length()).map { l.getInt(it) }
        smoothing = o.getInt("smoothing_h")
        val f = o.getJSONArray("features"); val c = o.getJSONArray("coef")
        coef = (0 until f.length()).associate { f.getString(it) to c.getDouble(it) }
        intercept = o.getDouble("intercept")
        pRef = o.getDouble("pressure_ref_hPa")
    }

    /** Surge in metres at hourly time t, or null when not enough weather history. */
    fun surgeAt(t: Long, wx: Map<Long, Weather>): Double? {
        var s = intercept
        for (lag in lags) {
            var p = 0.0; var tu = 0.0; var tv = 0.0
            for (k in 0 until smoothing) {
                val w = wx[t - (lag + k) * 3600L] ?: return null
                val r = Math.toRadians(w.windFromDeg)
                val u = -w.windMs * sin(r); val v = -w.windMs * cos(r)
                p += w.mslpHpa - pRef; tu += u * w.windMs; tv += v * w.windMs
            }
            s += (coef["p_$lag"] ?: 0.0) * p / smoothing +
                (coef["tu_$lag"] ?: 0.0) * tu / smoothing +
                (coef["tv_$lag"] ?: 0.0) * tv / smoothing
        }
        return s
    }
}

object Curves {
    /** Poole Tides model = astronomical + surge where weather is available. */
    fun model(astro: AstroTable, surge: SurgeModel, wx: Map<Long, Weather>, from: Long, to: Long): List<TidePoint> {
        val out = ArrayList<TidePoint>()
        var t = from - from % 600
        while (t <= to) {
            val a = astro.heightAt(t)
            if (a != null) {
                val h0 = t - t % 3600
                val s0 = surge.surgeAt(h0, wx)
                val s1 = surge.surgeAt(h0 + 3600, wx)
                val s = if (s0 != null && s1 != null) s0 + (s1 - s0) * (t - h0) / 3600.0 else s0 ?: 0.0
                out += TidePoint(t, a + s)
            }
            t += 600
        }
        return out
    }

    /**
     * UKHO free tier gives high and low water events only; join them with the
     * standard cosine curve so they can be drawn alongside the other curves.
     */
    fun fromEvents(events: List<TidePoint>, step: Long = 600): List<TidePoint> {
        val e = events.sortedBy { it.t }
        val out = ArrayList<TidePoint>()
        for (i in 0 until e.size - 1) {
            val a = e[i]; val b = e[i + 1]
            var t = a.t
            while (t < b.t) {
                val f = (t - a.t).toDouble() / (b.t - a.t)
                out += TidePoint(t, a.h + (b.h - a.h) * (1 - cos(Math.PI * f)) / 2)
                t += step
            }
        }
        e.lastOrNull()?.let { out += it }
        return out
    }
}
