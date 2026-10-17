package dev.pact.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pact.app.ui.Format
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HOUR: Dp = 56.dp
private val GUTTER: Dp = 44.dp

/** A timed entry laid out in one day column, in minutes from midnight. */
private data class Placed(val entry: CalEntry, val top: Int, val bottom: Int, val lane: Int, val lanes: Int)

/** Side-by-side lanes for overlapping entries, like most calendar apps. */
private fun layoutDay(day: LocalDate, entries: List<CalEntry>): List<Placed> {
    val items = entries.filter { !it.allDay && it.covers(day) }.map { e ->
        val top = if (e.date.isBefore(day)) 0 else e.start.toLocalTime().toSecondOfDay() / 60
        val bottom = if (e.end.toLocalDate().isAfter(day)) 1440 else e.end.toLocalTime().toSecondOfDay() / 60
        Triple(e, top, maxOf(bottom, top + 20))
    }.sortedWith(compareBy({ it.second }, { -it.third }))
    val out = mutableListOf<Placed>()
    var cluster = mutableListOf<Triple<CalEntry, Int, Int>>()
    var clusterEnd = -1
    val laneOf = HashMap<CalEntry, Int>()
    fun flush() {
        val n = (cluster.maxOfOrNull { laneOf[it.first] ?: 0 } ?: -1) + 1
        cluster.forEach { out += Placed(it.first, it.second, it.third, laneOf[it.first] ?: 0, n) }
        cluster = mutableListOf()
    }
    val laneEnds = mutableListOf<Int>()
    for (it in items) {
        if (it.second >= clusterEnd) {
            flush()
            laneEnds.clear()
        }
        val free = laneEnds.indexOfFirst { end -> end <= it.second }
        val lane = if (free >= 0) free else laneEnds.size
        if (free >= 0) laneEnds[free] = it.third else laneEnds += it.third
        laneOf[it.first] = lane
        cluster += it
        clusterEnd = maxOf(clusterEnd, it.third)
    }
    flush()
    return out
}

/**
 * Week or day view: day headers, an all-day strip and a scrollable 24-hour grid.
 * Tapping an empty slot calls [onNew] with that half hour.
 */
@Composable
fun TimeGrid(
    days: List<LocalDate>,
    entries: List<CalEntry>,
    onEntry: (CalEntry) -> Unit,
    onNew: (LocalDateTime) -> Unit,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR.toPx() }
    LaunchedEffect(days.first()) {
        val start = if (days.contains(today)) LocalTime.now().hour - 2 else 7
        scroll.scrollTo((start.coerceIn(0, 20) * hourPx).toInt())
    }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = LocalTime.now()
        }
    }

    Column(modifier.fillMaxSize()) {
        // Day headers
        Row(Modifier.fillMaxWidth().padding(start = GUTTER)) {
            for (d in days) {
                Column(Modifier.weight(1f).clickable { onDay(d) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEE")), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (d == today) cs.primary else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${d.dayOfMonth}", fontWeight = FontWeight.Bold, color = if (d == today) cs.onPrimary else cs.onSurface)
                    }
                }
            }
        }
        // All-day strip
        val allDay = days.map { d -> entries.filter { it.allDay && it.covers(d) } }
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(start = GUTTER, bottom = 4.dp)) {
                for (list in allDay) {
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        list.take(3).forEach { e -> Chip(e, Modifier.fillMaxWidth().clickable { onEntry(e) }) }
                        if (list.size > 3) Text("+${list.size - 3}", fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = cs.outlineVariant)
        // Hours
        Row(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = 88.dp)) {
            Column(Modifier.width(GUTTER)) {
                for (h in 0 until 24) {
                    Box(Modifier.height(HOUR).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                        if (h > 0) Text(Format.time(LocalTime.of(h, 0)).replace(":00", ""), fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp).offset(y = (-7).dp))
                    }
                }
            }
            for (d in days) {
                BoxWithConstraints(
                    Modifier.weight(1f).height(HOUR * 24)
                        .drawBehind {
                            for (h in 1 until 24) drawLine(cs.outlineVariant, Offset(0f, h * hourPx), Offset(size.width, h * hourPx), 1f)
                            drawLine(cs.outlineVariant, Offset(0f, 0f), Offset(0f, size.height), 1f)
                        }
                        .pointerInput(d) {
                            detectTapGestures { pos ->
                                val minutes = ((pos.y / hourPx) * 60).toInt() / 30 * 30
                                onNew(d.atTime(LocalTime.of((minutes / 60).coerceIn(0, 23), minutes % 60)))
                            }
                        },
                ) {
                    val colW = maxWidth
                    for (pl in layoutDay(d, entries)) {
                        val w = colW / pl.lanes
                        Box(
                            Modifier
                                .offset(x = w * pl.lane, y = HOUR * (pl.top / 60f))
                                .width(w)
                                .height(HOUR * ((pl.bottom - pl.top) / 60f))
                                .padding(1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (pl.entry.projected) pl.entry.color.copy(alpha = 0.35f) else pl.entry.color.copy(alpha = 0.85f))
                                .clickable { onEntry(pl.entry) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Column {
                                Text(
                                    pl.entry.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.background,
                                    maxLines = if (pl.bottom - pl.top < 45) 1 else 3, overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (pl.entry.done) TextDecoration.LineThrough else null,
                                )
                                if (pl.bottom - pl.top >= 45) {
                                    Text(Format.time(pl.entry.start.toLocalTime()), fontSize = 10.sp, color = cs.background.copy(alpha = 0.85f), maxLines = 1)
                                }
                            }
                        }
                    }
                    if (d == today) {
                        val y = HOUR * (now.toSecondOfDay() / 3600f)
                        Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(cs.error))
                        Box(Modifier.offset(x = (-4).dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(cs.error))
                    }
                }
            }
        }
    }
}

/** A small colored label for all-day entries and month cells. */
@Composable
fun Chip(e: CalEntry, modifier: Modifier = Modifier, fontSize: Int = 10) {
    val bg = if (e.projected) e.color.copy(alpha = 0.35f) else e.color
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 3.dp, vertical = 1.dp)) {
        Text(
            e.title, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Clip, color = MaterialTheme.colorScheme.background,
            fontWeight = FontWeight.Medium, textDecoration = if (e.done) TextDecoration.LineThrough else null,
        )
    }
}
