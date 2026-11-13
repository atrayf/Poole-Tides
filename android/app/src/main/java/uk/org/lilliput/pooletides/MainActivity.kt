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
    val messages: List<String> = emptyList(),
    val dayOffset: Int = 0,
)

class TidesViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("poole_tides", Context.MODE_PRIVATE)
    private val astro = AstroTable(app.assets.open("poole_astro_10min.bin").readBytes())
    private val surge = SurgeModel(app.assets.open("surge_model.json").bufferedReader().readText())
    var state by mutableStateOf(UiState()); private set

    var ukhoKey: String
        get() = prefs.getString("ukho_key", "") ?: ""
        set(v) { prefs.edit().putString("ukho_key", v.trim()).remove("ukho_station").apply(); refresh() }

    init { refresh() }

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
                        Ukho.events(key, id)
                    }
                }
            }
            Triple(obs.await(), wx.await(), uk.await())
        }
        val observed = r.first.getOrElse { msgs += "Poole Quay gauge: ${it.message}"; emptyList() }
        val wx = r.second.getOrElse { msgs += "Weather (surge correction off): ${it.message}"; emptyMap() }
        val events = r.third.getOrElse { msgs += "UKHO: ${it.message}"; null }
        if (ukhoKey.isBlank()) msgs += "Add your free UKHO key in Settings to show the UKHO curve."
        state = state.copy(
            loading = false,
            observed = observed,
            model = Curves.model(astro, surge, wx, from, to),
            ukhoEvents = events ?: emptyList(),
            ukho = events?.let { Curves.fromEvents(it) } ?: emptyList(),
            ukhoStation = prefs.getString("ukho_station_name", null),
            messages = msgs,
        )
    }
}

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
    val dayStart = Instant.now().atZone(LONDON).toLocalDate().plusDays(s.dayOffset.toLong())
    val from = dayStart.atStartOfDay(LONDON).toEpochSecond()
    val to = from + 86400

    Scaffold(topBar = {
        TopAppBar(title = { Text("Poole Tides") }, actions = {
            TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.shiftDay(-1) }, enabled = s.dayOffset > -2) { Text("◀") }
                Text(dayStart.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
                    Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                TextButton(onClick = { vm.shiftDay(1) }, enabled = s.dayOffset < 6) { Text("▶") }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            TideChart(s, from, to, Modifier.fillMaxWidth().height(320.dp))
            Spacer(Modifier.height(8.dp))
            Legend(OBS_COLOR, "Poole Quay observed (EA gauge)", false)
            Legend(MODEL_COLOR, "Poole Tides model (gauge harmonics + Bournemouth pressure/wind)", false)
            Legend(UKHO_COLOR, "UKHO ${s.ukhoStation ?: ""} (Absolute Tides source)", true)
            Spacer(Modifier.height(12.dp))
            HighLowTable(s, from, to)
            s.messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            Text("Heights in metres above Poole chart datum (1.40 m below ODN). Times are UK local.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showSettings) SettingsDialog(vm.ukhoKey, onDismiss = { showSettings = false }) {
        vm.ukhoKey = it; showSettings = false
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

@Composable
fun TideChart(s: UiState, from: Long, to: Long, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    val nowLine = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val left = 36.dp.toPx(); val bottom = size.height - 22.dp.toPx(); val top = 8.dp.toPx()
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
        for (hr in 0..24 step 3) {
            val t = from + hr * 3600L
            drawLine(grid, Offset(x(t), top), Offset(x(t), bottom), 1f)
            if (hr < 24) drawContext.canvas.nativeCanvas.drawText("%02d".format(hr), x(t) + 2, size.height - 4, paint)
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
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRectSafe(
    l: Float, r: Float, t: Float, b: Float, block: () -> Unit,
) = drawContext.canvas.run { save(); clipRect(l, t, r, b); block(); restore() }

@Composable
fun HighLowTable(s: UiState, from: Long, to: Long) {
    val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(LONDON)
    Text("High and low waters", fontWeight = FontWeight.SemiBold)
    val ukhoEv = s.ukhoEvents.filter { it.t in from..to }
    val model = Curves.turningPoints(s.model, from, to)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Poole Tides model", color = MODEL_COLOR, fontSize = 13.sp)
            model.forEach { (p, high) ->
                Text("${if (high) "HW" else "LW"} ${fmt.format(Instant.ofEpochSecond(p.t))}  %.2f m".format(p.h), fontSize = 13.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("UKHO", color = UKHO_COLOR, fontSize = 13.sp)
            if (ukhoEv.isEmpty()) Text("–", fontSize = 13.sp)
            ukhoEv.forEachIndexed { i, p ->
                val high = (ukhoEv.getOrNull(i + 1)?.let { p.h > it.h } ?: ukhoEv.getOrNull(i - 1)?.let { p.h > it.h }) ?: true
                Text("${if (high) "HW" else "LW"} ${fmt.format(Instant.ofEpochSecond(p.t))}  %.2f m".format(p.h), fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
fun SettingsDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("UKHO API key") },
        text = {
            Column {
                Text("Free key from the ADMIRALTY Developer Portal (UK Tidal API, Discovery tier). Stored only on this phone.",
                    fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(key, { key = it }, singleLine = true, label = { Text("Subscription key") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
