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
        assert(highs.size in 1..2)
        assert(lows.all { it.first.h < 1.5 }) { "low waters should be true lows: $lows" }
        assert(tp.none { it.first.t == day || it.first.t == day + 86400 }) { "no events at midnight: $tp" }
        // alternate HW/LW
        tp.zipWithNext().forEach { (a, b) -> assert(a.second != b.second) { "not alternating: $tp" } }
    }

    @Test
    fun pastedKeyIsCleaned() {
        assertEquals("abc123", cleanKey("  abc123\n"))
        assertEquals("abc123", cleanKey("abc123\nabc123"))
        assertEquals("", cleanKey("   "))
    }
}
