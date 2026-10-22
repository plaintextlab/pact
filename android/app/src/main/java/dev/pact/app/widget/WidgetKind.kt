package dev.pact.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.background
import androidx.glance.unit.ColorProvider

/** Which widget an app-widget id was placed as. */
enum class WidgetKind { TASKS, CALENDAR, UNKNOWN }

/**
 * Looks up the widget's own provider rather than trusting which GlanceAppWidget class
 * was asked to draw it, so a task widget can never end up showing the calendar or the
 * other way round, whatever path triggered the redraw.
 */
fun widgetKind(context: Context, id: GlanceId): WidgetKind = runCatching {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    when (AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className) {
        CalendarWidgetReceiver::class.java.name -> WidgetKind.CALENDAR
        PactWidgetReceiver::class.java.name -> WidgetKind.TASKS
        else -> WidgetKind.UNKNOWN
    }
}.getOrDefault(WidgetKind.UNKNOWN)

/**
 * A rounded background drawn from a shape drawable. Unlike `cornerRadius`, which relies on
 * view clipping that launchers skip in their long-press/edit previews, this stays round everywhere.
 */
fun GlanceModifier.shapeBackground(color: Color, @DrawableRes shape: Int): GlanceModifier =
    background(ImageProvider(shape), colorFilter = ColorFilter.tint(ColorProvider(color)))
