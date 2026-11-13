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
    /** Centred running mean over 2k+1 points, for noisy gauge readings. */
    fun smooth(pts: List<TidePoint>, k: Int): List<TidePoint> = pts.indices.map { i ->
        val win = pts.subList(maxOf(0, i - k), minOf(pts.size, i + k + 1))
        TidePoint(pts[i].t, win.sumOf { it.h } / win.size)
    }

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
    /**
     * High and low waters within [from, to]. Poole has a long double high-water stand,
     * so the dip between the two highs is not a low water: a low water is a local
     * minimum (over +/-90 min) below the 25-hour mean level, and the high water is
     * the highest point between consecutive low waters. Uses data up to 12 h either
     * side of the window so nothing is invented at midnight.
     */
    fun turningPoints(pts: List<TidePoint>, from: Long, to: Long): List<Pair<TidePoint, Boolean>> {
        val p = pts.filter { it.t in from - 43200..to + 43200 }.sortedBy { it.t }
        if (p.size < 3) return emptyList()
        val half = 45000L // ~12.5 h each side for the running mean
        val lows = ArrayList<Int>()
        for (i in p.indices) {
            val c = p[i]
            val near = p.filter { kotlin.math.abs(it.t - c.t) <= 5400 }
            if (near.first().t > c.t - 5400 || near.last().t < c.t + 5400) continue // need full window
            if (near.any { it.h < c.h }) continue
            val mean = p.filter { kotlin.math.abs(it.t - c.t) <= half }.map { it.h }.average()
            if (c.h >= mean) continue
            if (lows.isNotEmpty() && c.t - p[lows.last()].t < 3 * 3600) continue // flat bottom duplicate
            lows += i
        }
        val out = ArrayList<Pair<TidePoint, Boolean>>()
        for (k in lows.indices) {
            if (k > 0) {
                for (hw in highsBetween(p.subList(lows[k - 1], lows[k] + 1))) {
                    if (hw.t in from..to) out += hw to true
                }
            }
            if (p[lows[k]].t in from..to) out += p[lows[k]] to false
        }
        return out.sortedBy { it.first.t }
    }

    /**
     * The high water(s) between two low waters. Poole often has a double high:
     * two peaks at least 2 h apart with a dip of 3 cm or more between them.
     * Then both are returned; otherwise just the highest point.
     */
    private fun highsBetween(seg: List<TidePoint>): List<TidePoint> {
        val top = seg.maxBy { it.h }
        val peaks = seg.indices.filter { i ->
            i > 0 && i < seg.size - 1 &&
                seg.filter { kotlin.math.abs(it.t - seg[i].t) <= 3600 }.all { it.h <= seg[i].h } &&
                seg[i - 1].h < seg[i].h
        }.map { seg[it] }
        val second = peaks.filter { kotlin.math.abs(it.t - top.t) >= 7200 }.maxByOrNull { it.h }
            ?: return listOf(top)
        val (a, b) = if (second.t < top.t) second to top else top to second
        val dip = seg.filter { it.t in a.t..b.t }.minOf { it.h }
        return if (minOf(a.h, b.h) - dip >= 0.03) listOf(a, b) else listOf(top)
    }

    /**
     * Draws a source's HW/LW events with the shape of our own harmonic curve between
     * them, so Poole's double high water and young-flood stand show. Each event is
     * matched to the nearest model turning point of the same kind (within 3 h), then
     * the model curve between matched points is stretched in time and scaled in
     * height to pass exactly through the source's events. Falls back to a cosine
     * join where no match is found.
     */
    fun shapedFromEvents(events: List<TidePoint>, astro: (Long) -> Double?, step: Long = 600): List<TidePoint> {
        val e = events.sortedBy { it.t }
        if (e.size < 2) return e
        val span = (e.first().t - 86400)..(e.last().t + 86400)
        val ref = span.step(step).mapNotNull { t -> astro(t)?.let { TidePoint(t, it) } }
        val tps = turningPoints(ref, span.first, span.last)
        fun isHigh(i: Int) = (e.getOrNull(i + 1)?.let { e[i].h > it.h } ?: e.getOrNull(i - 1)?.let { e[i].h > it.h }) ?: true
        val match = e.indices.map { i ->
            val high = isHigh(i)
            tps.filter { it.second == high && kotlin.math.abs(it.first.t - e[i].t) <= 3 * 3600 }
                .minByOrNull { kotlin.math.abs(it.first.t - e[i].t) }?.first
        }
        val out = ArrayList<TidePoint>()
        for (i in 0 until e.size - 1) {
            val a = e[i]; val b = e[i + 1]; val ma = match[i]; val mb = match[i + 1]
            val shaped = ma != null && mb != null && mb.t > ma.t && kotlin.math.abs(mb.h - ma.h) > 0.05
            var t = a.t
            while (t < b.t) {
                val f = (t - a.t).toDouble() / (b.t - a.t)
                val h = if (shaped) {
                    val tm = ma!!.t + (f * (mb!!.t - ma.t)).toLong()
                    val am = astro(tm)
                    if (am == null) null else a.h + (am - ma.h) / (mb.h - ma.h) * (b.h - a.h)
                } else null
                out += TidePoint(t, h ?: (a.h + (b.h - a.h) * (1 - cos(Math.PI * f)) / 2))
                t += step
            }
        }
        out += e.last()
        return out
    }
}
