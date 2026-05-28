package com.selimdurmus.dictionary.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.selimdurmus.dictionary.R

class QuickSearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            val options = appWidgetManager.getAppWidgetOptions(id)
            applyViews(context, appWidgetManager, id, options)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        applyViews(context, appWidgetManager, appWidgetId, newOptions)
    }

    private fun applyViews(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        options: Bundle,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_search)

        // OPTION_APPWIDGET_MIN_WIDTH is in dp and reflects the current widget size after resize.
        // Pixel-style launcher cells run ~95-110dp; thresholds split between 1/2/3+ cells.
        val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        when {
            minWidthDp in 1..CELL_1_MAX -> {
                views.setViewVisibility(R.id.widget_label, View.GONE)
            }
            minWidthDp in (CELL_1_MAX + 1)..CELL_2_MAX -> {
                views.setViewVisibility(R.id.widget_label, View.VISIBLE)
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_label_short))
            }
            else -> {
                views.setViewVisibility(R.id.widget_label, View.VISIBLE)
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_label))
            }
        }

        val intent = Intent(context, QuickSearchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        private const val CELL_1_MAX = 110   // up to ~1 cell wide
        private const val CELL_2_MAX = 220   // up to ~2 cells wide
    }
}
