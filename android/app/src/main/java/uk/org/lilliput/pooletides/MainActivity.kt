package uk.org.lilliput.pooletides

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Bright series colours chosen to read on the dark background
val OBS_COLOR = Color(0xFF66BB6A)
val MODEL_COLOR = Color(0xFF64B5F6)
val UKHO_COLOR = Color(0xFFFFA726)

/** Always-dark marine palette. */
private val PooleDark = darkColorScheme(
    primary = Color(0xFF7FD1F0),
    background = Color(0xFF0B1620),
    surface = Color(0xFF0B1620),
    surfaceVariant = Color(0xFF16242F),
    onBackground = Color(0xFFE3EAF0),
    onSurface = Color(0xFFE3EAF0),
    onSurfaceVariant = Color(0xFFA9B8C4),
    outlineVariant = Color(0xFF2A3A47),
    error = Color(0xFFFF8A80),
)
private val LONDON = ZoneId.of("Europe/London")

data class UiState(
    val loading: Boolean = true,
    val observed: List<TidePoint> = emptyList(),
    val model: List<TidePoint> = emptyList(),
    val ukho: List<TidePoint> = emptyList(),
    val ukhoEvents: List<TidePoint> = emptyList(),
    val ukhoStation: String? = null,
    val thirdLabel: String = "UKHO",
    val nowWeather: Weather? = null,
    val messages: List<String> = emptyList(),
    val dayOffset: Int = 0,
)

class TidesViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("poole_tides", Context.MODE_PRIVATE)
    private val astro = AstroTable(app.assets.open("poole_astro_10min.bin").readBytes())
    private val surge = SurgeModel(app.assets.open("surge_model.json").bufferedReader().readText())
    var state by mutableStateOf(UiState()); private set

    var ukhoKey: String
        get() = cleanKey(prefs.getString("ukho_key", "") ?: "")
        set(v) { prefs.edit().putString("ukho_key", cleanKey(v)).remove("ukho_station").apply(); refresh() }

    var worldTidesKey: String
        get() = cleanKey(prefs.getString("wt_key", "") ?: "")
        set(v) { prefs.edit().putString("wt_key", cleanKey(v)).remove("wt_cache").remove("wt_cache_at").apply(); refresh() }

    init { refresh() }

    /** UKHO's free tier only returns events from today; keep earlier ones so the curve covers the whole day. */
    private fun mergeUkhoCache(fresh: List<TidePoint>): List<TidePoint> {
        val old = runCatching {
            org.json.JSONArray(prefs.getString("ukho_cache", "[]")).let { a ->
                (0 until a.length()).map { val o = a.getJSONObject(it); TidePoint(o.getLong("t"), o.getDouble("h")) }
            }
        }.getOrDefault(emptyList())
        val cutoff = Instant.now().epochSecond - 4 * 86400
        val firstFresh = fresh.minOfOrNull { it.t } ?: Long.MAX_VALUE
        val merged = (old.filter { it.t < firstFresh - 1800 && it.t > cutoff } + fresh).sortedBy { it.t }
        val a = org.json.JSONArray()
        merged.forEach { a.put(org.json.JSONObject().put("t", it.t).put("h", it.h)) }
        prefs.edit().putString("ukho_cache", a.toString()).apply()
        return merged
    }

    /** WorldTides costs credits: reuse a fetch for 12 hours. */
    private fun worldTides(key: String, from: Long): List<TidePoint> {
        val at = prefs.getLong("wt_cache_at", 0)
        val now = Instant.now().epochSecond
        if (now - at < 12 * 3600) runCatching {
            val a = org.json.JSONArray(prefs.getString("wt_cache", "[]"))
            val pts = (0 until a.length()).map { val o = a.getJSONObject(it); TidePoint(o.getLong("t"), o.getDouble("h")) }
            if (pts.isNotEmpty()) return pts
        }
        val pts = WorldTides.heights(key, from - from % 86400, days = 10)
        val a = org.json.JSONArray(); pts.forEach { a.put(org.json.JSONObject().put("t", it.t).put("h", it.h)) }
        prefs.edit().putString("wt_cache", a.toString()).putLong("wt_cache_at", now).apply()
        return pts
    }

    fun shiftDay(d: Int) { state = state.copy(dayOffset = (state.dayOffset + d).coerceIn(-2, 6)) }

    fun refresh() = viewModelScope.launch {
        state = state.copy(loading = true, messages = emptyList())
        val now = Instant.now().epochSecond
        val from = now - 3 * 86400; val to = now + 7 * 86400
        val msgs = mutableListOf<String>()
        val r = withContext(Dispatchers.IO) {
            val obs = async { runCatching { PooleGauge.fetch(from) } }
            val wx = async { runCatching { BournemouthWeather.fetch() } }
            val uk = async {
                runCatching {
                    val key = ukhoKey
                    if (key.isBlank()) null else {
                        val id = prefs.getString("ukho_station", null) ?: Ukho.findPooleStation(key).let { (id, name) ->
                            prefs.edit().putString("ukho_station", id).putString("ukho_station_name", name).apply(); id
                        }
                        mergeUkhoCache(Ukho.events(key, id))
                    }
                }
            }
            val wt = async { runCatching { worldTidesKey.takeIf { it.isNotBlank() }?.let { worldTides(it, from) } } }
            listOf(obs.await(), wx.await(), uk.await(), wt.await())
        }
        @Suppress("UNCHECKED_CAST") val rObs = r[0] as Result<List<TidePoint>>
        @Suppress("UNCHECKED_CAST") val rWx = r[1] as Result<Map<Long, Weather>>
        @Suppress("UNCHECKED_CAST") val rUk = r[2] as Result<List<TidePoint>?>
        @Suppress("UNCHECKED_CAST") val rWt = r[3] as Result<List<TidePoint>?>
        val keys = arrayOf(ukhoKey, worldTidesKey)
        val observed = rObs.getOrElse { msgs += "Poole Quay gauge: ${redact(it.message, *keys)}"; emptyList() }
        val wx = rWx.getOrElse { msgs += "Weather (surge correction off): ${redact(it.message, *keys)}"; emptyMap() }
        val events = rUk.getOrElse { msgs += "UKHO: ${redact(it.message, *keys)}"; null }
        val wtCurve = rWt.getOrElse { msgs += "WorldTides: ${redact(it.message, *keys)}"; null }
        if (ukhoKey.isBlank() && wtCurve == null) msgs += "Add your free UKHO key in Settings to show the third curve."
        state = state.copy(
            loading = false,
            observed = observed,
            model = Curves.model(astro, surge, wx, from, to),
            ukhoEvents = events ?: emptyList(),
            ukho = wtCurve ?: events?.let { Curves.shapedFromEvents(it, astro::heightAt) } ?: emptyList(),
            ukhoStation = prefs.getString("ukho_station_name", null),
            thirdLabel = if (wtCurve != null) "WorldTides" else "UKHO",
            nowWeather = wx[now - now % 3600],
            messages = msgs,
        )
    }
}

/** A pasted key may carry line breaks or be pasted twice: keep only the first token. */
fun cleanKey(raw: String): String = raw.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PooleDark) {
                Surface(Modifier.fillMaxSize()) { TidesScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidesScreen(vm: TidesViewModel = viewModel()) {
    val s = vm.state
    var showSettings by remember { mutableStateOf(false) }
    var showTable by remember { mutableStateOf(false) }
    val dayStart = Instant.now().atZone(LONDON).toLocalDate().plusDays(s.dayOffset.toLong())
    val from = dayStart.atStartOfDay(LONDON).toEpochSecond()
    val to = from + 86400

    Scaffold(topBar = {
        TopAppBar(title = { Text("Poole Tides") }, actions = {
            TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.shiftDay(-1) }, enabled = s.dayOffset > -2) { Text("◀") }
                Text(dayStart.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
                    Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                TextButton(onClick = { vm.shiftDay(1) }, enabled = s.dayOffset < 6) { Text("▶") }
                OutlinedButton(onClick = { showTable = true }) { Text("HW / LW") }
            }
            s.nowWeather?.let { NowWeather(it) }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            // The chart takes all the height left after the legend and notes
            TideChart(s, from, to, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(8.dp))
            Legend(OBS_COLOR, "Poole Quay observed (EA gauge)", false)
            Legend(MODEL_COLOR, "Poole Tides model (gauge harmonics + Bournemouth pressure/wind)", false)
            Legend(UKHO_COLOR, if (s.thirdLabel == "WorldTides") "WorldTides (old Lilliput app source)"
                else "UKHO ${s.ukhoStation ?: ""} HW/LW, shaped by our harmonics", true)
            s.messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            Text("Heights in metres above Poole chart datum (1.40 m below ODN). Times are UK local.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showTable) AlertDialog(onDismissRequest = { showTable = false },
        title = { Text("High and low waters – " + dayStart.format(DateTimeFormatter.ofPattern("EEE d MMM"))) },
        text = { Column { HighLowTable(s, from, to) } },
        confirmButton = { TextButton(onClick = { showTable = false }) { Text("Close") } })
    if (showSettings) SettingsDialog(vm.ukhoKey, vm.worldTidesKey, onDismiss = { showSettings = false }) { uk, wt ->
        if (uk != vm.ukhoKey) vm.ukhoKey = uk
        if (wt != vm.worldTidesKey) vm.worldTidesKey = wt
        showSettings = false
    }
}

private val COMPASS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

/** Current Bournemouth wind and pressure, the inputs to the surge correction. */
@Composable
fun NowWeather(w: Weather) {
    val kn = w.windMs * 1.943844
    val dir = COMPASS[(((w.windFromDeg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Wind ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%s %.0f kn".format(dir, kn), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(20.dp))
        Text("Pressure ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%.0f hPa".format(w.mslpHpa), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("Bournemouth, now", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Legend(c: Color, label: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Canvas(Modifier.size(28.dp, 10.dp)) {
            drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 6f,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null)
        }
        Spacer(Modifier.width(8.dp)); Text(label, fontSize = 13.sp)
    }
}

/** HW/LW of the third source in [from, to]: WorldTides curve turning points, or UKHO's own events. */
fun thirdTurningPoints(s: UiState, from: Long, to: Long): List<Pair<TidePoint, Boolean>> =
    if (s.thirdLabel == "WorldTides") Curves.turningPoints(s.ukho, from, to)
    else {
        val ev = s.ukhoEvents
        ev.mapIndexed { i, p -> p to ((ev.getOrNull(i + 1)?.let { p.h > it.h } ?: ev.getOrNull(i - 1)?.let { p.h > it.h }) ?: true) }
            .filter { it.first.t in from..to }
    }

private data class Label(val text: String, val x: Float, val y: Float, val high: Boolean, val color: Color)

@Composable
fun TideChart(s: UiState, from: Long, to: Long, modifier: Modifier) {
    val modelTp = remember(s.model, from) { Curves.turningPoints(s.model, from, to) }
    val thirdTp = remember(s.ukho, s.ukhoEvents, from) { thirdTurningPoints(s, from, to) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    val nowLine = MaterialTheme.colorScheme.primary
    val labelBg = MaterialTheme.colorScheme.background.copy(alpha = 0.8f)
    Canvas(modifier) {
        // room above and below the plot for HW/LW labels (two rows each)
        val left = 36.dp.toPx(); val bottom = size.height - 18.dp.toPx() - 40.dp.toPx(); val top = 56.dp.toPx()
        val w = size.width - left
        val all = listOf(s.observed, s.model, s.ukho).flatten().filter { it.t in from..to }
        // Axis starts at 0.5 m; it only extends lower if a reading actually goes below that
        val lo = minOf(0.5, kotlin.math.floor((all.minOfOrNull { it.h } ?: 0.5) * 2) / 2)
        val hi = maxOf(2.5, all.maxOfOrNull { it.h } ?: 2.5)
        fun x(t: Long) = left + w * (t - from).toFloat() / (to - from)
        fun y(h: Double) = (bottom - (bottom - top) * (h - lo) / (hi - lo)).toFloat()
        val paint = android.graphics.Paint().apply { color = text.toArgb(); textSize = 11.sp.toPx(); isAntiAlias = true }
        var gh = kotlin.math.ceil(lo * 2) / 2
        while (gh <= hi) {
            drawLine(grid, Offset(left, y(gh)), Offset(size.width, y(gh)), 1f)
            drawContext.canvas.nativeCanvas.drawText("%.1f".format(gh), 0f, y(gh) + 4, paint)
            gh += 0.5
        }
        val hourBase = bottom + 14.dp.toPx()
        val reserved = mutableListOf<android.graphics.RectF>()
        for (hr in 0..24 step 3) {
            val t = from + hr * 3600L
            drawLine(grid, Offset(x(t), top), Offset(x(t), bottom), 1f)
            if (hr < 24) {
                drawContext.canvas.nativeCanvas.drawText("%02d".format(hr), x(t) + 2, hourBase, paint)
                reserved += android.graphics.RectF(x(t), hourBase - paint.textSize, x(t) + paint.measureText("00") + 4, hourBase + 2)
            }
        }
        fun series(pts: List<TidePoint>, c: Color, dashed: Boolean) {
            val p = Path(); var started = false; var last = 0L
            for (pt in pts) {
                if (pt.t < from - 3600 || pt.t > to + 3600) continue
                if (!started || pt.t - last > 3600) p.moveTo(x(pt.t), y(pt.h)) else p.lineTo(x(pt.t), y(pt.h))
                started = true; last = pt.t
            }
            drawPath(p, c, style = Stroke(width = 3.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(18f, 10f)) else null))
        }
        clipRectSafe(left, size.width, top, bottom) {
            series(s.ukho, UKHO_COLOR, true)
            series(s.model, MODEL_COLOR, false)
            series(s.observed, OBS_COLOR, false)
        }
        val now = Instant.now().epochSecond
        if (now in from..to) drawLine(nowLine, Offset(x(now), top), Offset(x(now), bottom), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawTurningLabels(modelTp.map { it to MODEL_COLOR } + thirdTp.map { it to UKHO_COLOR }, ::x, ::y,
            left, size.width, 2f, size.height - 2f, reserved, labelBg)
    }
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(LONDON)

/**
 * Labels each high/low with its height (m) above its time, in its curve's colour. Highs go above
 * the point, lows below; each label takes the first slot (nearest first, then the
 * other side) that does not overlap a label already placed. A label with no free
 * slot is left off rather than drawn over another.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTurningLabels(
    pts: List<Pair<Pair<TidePoint, Boolean>, Color>>, x: (Long) -> Float, y: (Double) -> Float,
    minX: Float, maxX: Float, minY: Float, maxY: Float,
    reserved: List<android.graphics.RectF>, bg: Color,
) {
    val paint = android.graphics.Paint().apply { textSize = 11.sp.toPx(); isAntiAlias = true; isFakeBoldText = true }
    val lineH = paint.fontSpacing
    val gap = 6.dp.toPx()
    val placed = reserved.toMutableList()
    val bgPaint = android.graphics.Paint().apply { color = bg.toArgb(); isAntiAlias = true }
    val dots = pts.map { (tp, color) -> Triple(x(tp.first.t), y(tp.first.h), color) }
    dots.forEach { (px, py, color) -> drawCircle(color, 3.dp.toPx(), Offset(px, py)) }
    // label higher curve points first so the main peaks get the nearest slots
    for ((tp, color) in pts.sortedByDescending { kotlin.math.abs(it.first.first.h - 1.6) }) {
        val (p, high) = tp
        val px = x(p.t); val py = y(p.h)
        // two lines: height (with unit) above, time below
        val top1 = "%.2f m".format(p.h)
        val top2 = HHMM.format(Instant.ofEpochSecond(p.t))
        val w = maxOf(paint.measureText(top1), paint.measureText(top2))
        val blockH = lineH * 2
        val l = (px - w / 2).coerceIn(minX, maxX - w)
        // candidate block tops: above the point stacking upwards, or below stacking downwards
        val above = (0..2).map { k -> py - gap - blockH - k * blockH }
        val below = (0..2).map { k -> py + gap + k * blockH }
        val slots = if (high) above + below else below + above
        val blockTop = slots.firstOrNull { t ->
            val r = android.graphics.RectF(l - 2, t, l + w + 2, t + blockH)
            r.top >= minY && r.bottom <= maxY && placed.none { android.graphics.RectF.intersects(it, r) }
        } ?: continue
        placed += android.graphics.RectF(l - 2, blockTop, l + w + 2, blockTop + blockH)
        paint.color = color.toArgb()
        val c = drawContext.canvas.nativeCanvas
        // dark backing so curves and grid lines pass behind the text
        c.drawRoundRect(l - 3, blockTop, l + w + 3, blockTop + blockH, 4.dp.toPx(), 4.dp.toPx(), bgPaint)
        c.drawText(top1, l + (w - paint.measureText(top1)) / 2, blockTop + lineH * 0.8f, paint)
        c.drawText(top2, l + (w - paint.measureText(top2)) / 2, blockTop + lineH * 1.8f, paint)
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRectSafe(
    l: Float, r: Float, t: Float, b: Float, block: () -> Unit,
) = drawContext.canvas.run { save(); clipRect(l, t, r, b); block(); restore() }

@Composable
fun HighLowTable(s: UiState, from: Long, to: Long) {
    val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(LONDON)
    val model = Curves.turningPoints(s.model, from, to)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Poole Tides model", color = MODEL_COLOR, fontSize = 13.sp)
            model.forEach { (p, high) ->
                Text("${if (high) "HW" else "LW"} ${fmt.format(Instant.ofEpochSecond(p.t))}  %.2f m".format(p.h), fontSize = 13.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(s.thirdLabel, color = UKHO_COLOR, fontSize = 13.sp)
            val third = thirdTurningPoints(s, from, to)
            if (third.isEmpty()) Text("–", fontSize = 13.sp)
            third.forEach { (p, high) ->
                Text("${if (high) "HW" else "LW"} ${fmt.format(Instant.ofEpochSecond(p.t))}  %.2f m".format(p.h), fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
fun SettingsDialog(ukho: String, worldTides: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var uk by remember { mutableStateOf(ukho) }
    var wt by remember { mutableStateOf(worldTides) }
    val hidden = androidx.compose.ui.text.input.PasswordVisualTransformation()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Data keys") },
        text = {
            Column {
                Text("UKHO: free key from the ADMIRALTY Developer Portal (UK Tidal API, Discovery tier).", fontSize = 13.sp)
                OutlinedTextField(uk, { uk = it }, singleLine = true, label = { Text("UKHO subscription key") },
                    visualTransformation = hidden)
                Spacer(Modifier.height(12.dp))
                Text("Optional WorldTides key (paid credits, as in the old Lilliput app). When set, the third curve uses WorldTides heights instead of UKHO.",
                    fontSize = 13.sp)
                OutlinedTextField(wt, { wt = it }, singleLine = true, label = { Text("WorldTides key") },
                    visualTransformation = hidden)
                Spacer(Modifier.height(8.dp))
                Text("Keys are stored only on this phone.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(uk, wt) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
