package uk.org.lilliput.pooletides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Checks the Kotlin model reproduces the Python fit: fixture files are written
 * by model/fit_model.py (weather used in the fit, and the model's own hourly
 * astronomical + surge values for the same hours).
 */
class TideModelTest {
    private val assets = File("src/main/assets")
    private val fixtures = File("src/test/resources")

    /** Values from the Python prototype of the NP159 method (model/admiralty_shm.py). */
    @Test
    fun admiraltyShmMatchesPython() {
        val expected = listOf(1794528000L to 1.8982, 1794538800L to 1.7388, 1794549600L to 0.9305, 1794560400L to 1.8826,
            1794571200L to 2.0157, 1794582000L to 1.6785, 1794592800L to 0.8265, 1794603600L to 1.5739,
            1813555200L to 0.5938, 1813580400L to 1.7893, 1813605600L to 1.5628)
        for ((t, h) in expected) assertEquals("t=$t", h, AdmiraltyShm.heightAt(t), 0.002)
    }

    @Test
    fun heightAtInterpolatesAndSkipsGaps() {
        val pts = listOf(TidePoint(0, 1.0), TidePoint(600, 2.0), TidePoint(10_000, 3.0))
        assertEquals(1.5, Curves.at(pts, 300)!!, 1e-9)
        assertEquals(2.0, Curves.at(pts, 600)!!, 1e-9)
        assertEquals(null, Curves.at(pts, 5000))   // gap over an hour
        assertEquals(null, Curves.at(pts, -1))     // before the series
        assertEquals(null, Curves.at(pts, 20_000)) // after it
    }

    @Test
    fun kotlinMatchesPythonModel() {
        val astro = AstroTable(File(assets, "poole_astro_10min.bin").readBytes())
        val surge = SurgeModel(File(assets, "surge_model.json").readText())
        val wx = File(fixtures, "weather.csv").readLines().drop(1).associate { line ->
            val (t, p, s, d) = line.split(",")
            t.toLong() to Weather(t.toLong(), p.toDouble(), s.toDouble(), d.toDouble())
        }
        var n = 0
        File(fixtures, "expected.csv").readLines().drop(1).forEach { line ->
            val (t, tide, surgeExp) = line.split(",")
            val a = astro.heightAt(t.toLong())
            assertNotNull(a)
            assertEquals("astro at $t", tide.toDouble(), a!!, 0.01)
            val s = surge.surgeAt(t.toLong(), wx)
            assertNotNull("surge at $t", s)
            assertEquals("surge at $t", surgeExp.toDouble(), s!!, 0.002)
            n++
        }
        assert(n > 100)
    }

    @Test
    fun cosineCurvePassesThroughEvents() {
        val ev = listOf(TidePoint(0, 0.5), TidePoint(6 * 3600, 2.0), TidePoint(12 * 3600, 0.6))
        val c = Curves.fromEvents(ev)
        assertEquals(0.5, c.first().h, 1e-9)
        assertEquals(2.0, c.first { it.t == 6 * 3600L }.h, 1e-9)
        assertEquals(1.25, c.first { it.t == 3 * 3600L }.h, 1e-9)
    }

    @Test
    fun highLowTableSkipsStandDipAndMidnight() {
        val astro = AstroTable(File(assets, "poole_astro_10min.bin").readBytes())
        val day = java.time.LocalDate.of(2026, 11, 14).atStartOfDay(java.time.ZoneId.of("Europe/London")).toEpochSecond()
        val pts = (day - 86400..day + 2 * 86400 step 600L).map { TidePoint(it, astro.heightAt(it)!!) }
        val tp = Curves.turningPoints(pts, day, day + 86400)
        val lows = tp.filter { !it.second }; val highs = tp.filter { it.second }
        assertEquals("two low waters", 2, lows.size)
        assert(highs.size in 1..4)
        assert(lows.all { it.first.h < 1.5 }) { "low waters should be true lows: $lows" }
        assert(tp.none { it.first.t == day || it.first.t == day + 86400 }) { "no events at midnight: $tp" }
        // never two lows in a row
        tp.zipWithNext().forEach { (a, b) -> assert(a.second || b.second) { "two lows in a row: $tp" } }
    }

    @Test
    fun pastedKeyIsCleaned() {
        assertEquals("abc123", cleanKey("  abc123\n"))
        assertEquals("abc123", cleanKey("abc123\nabc123"))
        assertEquals("", cleanKey("   "))
    }

    /** Synthetic Poole-like tide: M2 plus an M4 that splits the high water. */
    private fun pooleLike(t: Long): Double {
        val w = 2 * Math.PI / (12.42 * 3600)
        return 1.6 + 0.5 * kotlin.math.cos(w * t) + 0.22 * kotlin.math.cos(2 * w * t - Math.PI)
    }

    @Test
    fun doubleHighWaterListsBothPeaks() {
        val pts = (0L..4 * 86400 step 600).map { TidePoint(it, pooleLike(it)) }
        val tp = Curves.turningPoints(pts, 86400, 2 * 86400)
        val highs = tp.filter { it.second }
        val lows = tp.filter { !it.second }
        assert(highs.size >= 3) { "expected double highs, got $tp" }
        assert(lows.size in 1..2) { "the stand dip must not be a low: $tp" }
    }

    @Test
    fun shapedCurvePassesThroughEventsAndKeepsDoubleHigh() {
        val astro = { t: Long -> pooleLike(t) }
        val ref = (0L..4 * 86400 step 600).map { TidePoint(it, pooleLike(it)) }
        // fake "UKHO" events: model turning points shifted 10 min and lowered 5 cm, one HW per tide
        val tps = Curves.turningPoints(ref, 86400, 3 * 86400)
        val ev = mutableListOf<TidePoint>()
        for ((p, high) in tps) {
            if (high && ev.isNotEmpty() && ev.last().h > 1.8) continue
            ev += TidePoint(p.t + 600, p.h - 0.05)
        }
        val c = Curves.shapedFromEvents(ev, astro)
        for (e in ev) assertEquals(e.h, c.first { it.t == e.t }.h, 1e-6)
        val shaped = Curves.turningPoints(c, ev.first().t + 3600, ev.last().t - 3600).count { it.second }
        val cosine = Curves.turningPoints(Curves.fromEvents(ev), ev.first().t + 3600, ev.last().t - 3600).count { it.second }
        assert(shaped > cosine) { "shaped curve should show more highs ($shaped) than cosine ($cosine)" }
    }

    @Test
    fun keysAreRedacted() {
        assertEquals("bad header •••", redact("bad header abcdef123456\nabcdef123456", "abcdef123456"))
    }
}
