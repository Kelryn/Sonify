package com.sonoritmo.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sonoritmo.app.MainActivity
import com.sonoritmo.app.R
import com.sonoritmo.core.data.repository.SchedulingWorldRepository
import com.sonoritmo.core.domain.logic.ConflictResolver
import com.sonoritmo.core.domain.model.DesiredState
import com.sonoritmo.core.domain.port.TimeSource
import com.sonoritmo.core.system.scheduler.SchedulerCoordinator
import com.sonoritmo.core.system.scheduler.Trigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Home-screen widget showing the active profile.
 *
 * Kept deliberately small. Its real value is not the pixels: an app with an active widget
 * is exempt from Android's `restricted` standby bucket — the one that allows a single alarm
 * per day and quietly kills apps like this one. Every `onUpdate` is also a free
 * reconciliation point. See docs/02, decision D-C5.
 *
 * Glance's only stable release is 1.1.1. If it ever fights `compileSdk 36`, this whole
 * widget is a documented cut candidate and the tile carries the quick-access requirement on
 * its own — but the bucket exemption is worth defending first.
 */
class SonoRitmoWidget : GlanceAppWidget() {

    /** Glance has no Hilt integration, so dependencies are pulled from the singleton graph. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDependencies {
        fun worldRepository(): SchedulingWorldRepository
        fun coordinator(): SchedulerCoordinator
        fun timeSource(): TimeSource
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetDependencies::class.java,
        )

        deps.coordinator().reconcile(Trigger.USER_INTERACTION)

        val world = deps.worldRepository().load()
        val desired = ConflictResolver.resolve(world, deps.timeSource().now())

        val title = when (desired) {
            is DesiredState.Active -> desired.profile.name
            is DesiredState.Paused -> context.getString(R.string.widget_paused)
            DesiredState.Idle -> context.getString(R.string.widget_idle)
        }
        val subtitle = when (desired) {
            is DesiredState.Active -> context.getString(R.string.widget_active_subtitle)
            is DesiredState.Paused -> context.getString(R.string.widget_paused_subtitle)
            DesiredState.Idle -> context.getString(R.string.widget_idle_subtitle)
        }

        provideContent {
            GlanceTheme {
                WidgetBody(title = title, subtitle = subtitle)
            }
        }
    }
}

@Composable
private fun WidgetBody(title: String, subtitle: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp())
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.Start,
    ) {
        Text(
            text = title,
            style = TextStyle(color = ColorProvider(day = androidx.compose.ui.graphics.Color.Black, night = androidx.compose.ui.graphics.Color.White)),
        )
        Text(
            text = subtitle,
            style = TextStyle(color = ColorProvider(day = androidx.compose.ui.graphics.Color.DarkGray, night = androidx.compose.ui.graphics.Color.LightGray)),
        )
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

class SonoRitmoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SonoRitmoWidget()
}
