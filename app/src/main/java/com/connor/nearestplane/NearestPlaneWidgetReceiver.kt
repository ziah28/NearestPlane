package com.connor.nearestplane

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class NearestPlaneWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = NearestPlaneWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Nothing fetched until you tap refresh; just clear any old schedule.
        RefreshWorker.cancelPeriodic(context)
    }

    /**
     * Called when the widget is placed or the system asks it to redraw. It
     * redraws from saved state only: the plane changes when you tap refresh.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        RefreshWorker.cancelPeriodic(context)
    }
}
