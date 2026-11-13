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
}
