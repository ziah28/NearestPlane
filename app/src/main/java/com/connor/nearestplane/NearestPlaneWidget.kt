package com.connor.nearestplane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.util.concurrent.TimeUnit

/**
 * The boarding-pass tile: airline and callsign on top, the route as two airport
 * codes with the plane on a path between them, and distance, height and speed
 * along the bottom.
 */
class NearestPlaneWidget : GlanceAppWidget() {

    // Exact, so the route path can be drawn to the tile's real width.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Body(currentState()) }
    }

    private object Ink {
        val card = Color(0xFFFFFFFF)
        val ink = Color(0xFF0F1B2D)
        val muted = Color(0xFF5A6472)
        val rule = Color(0xFFE4E8EE)
        val track = Color(0xFFB8C0CC)
        val badge = Color(0xFFEEF1F6)
        val accent = Color(0xFF2F6FED)
        val warn = Color(0xFFB4400B)
    }

    private val codeWidth = 76
    private val gap = 12
    private val sidePad = 20

    @Composable
    private fun Body(prefs: Preferences) {
        val status = prefs[WidgetState.STATUS] ?: "never"

        // A tile that says "open the app" and then doesn't when tapped is worse
        // than one that says nothing. Everywhere else, tap opens the detail screen.
        val tap = if (status == "no_permission") actionStartActivity<MainActivity>()
        else actionStartActivity<PlaneDetailActivity>()

        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .wrapContentHeight()
                .background(Ink.card)
                .cornerRadius(28.dp)
                .clickable(tap)
                .padding(horizontal = sidePad.dp, vertical = 16.dp)
        ) {
            when (status) {
                "no_permission" -> Header("Overhead", "No location set · tap to set it up", "", "", null)
                "never" -> Header("Overhead", "Tap the arrow to find the plane overhead", "", "", refresh())
                "error" -> Header("Overhead", "Couldn't reach the flight data", "", ageText(prefs), refresh(), warn = true)
                "empty" -> Header(
                    prefs[WidgetState.TITLE] ?: "Clear skies",
                    prefs[WidgetState.POSITION].orEmpty(),
                    "", ageText(prefs), refresh(), warn = hasProblem(prefs)
                )
                else -> Pass(prefs)
            }
        }
    }

    private fun refresh(): Action = actionRunCallback<PlaneRefreshAction>()

    @Composable
    private fun Pass(prefs: Preferences) {
        val airline = prefs[WidgetState.AIRLINE].orEmpty()
        val type = prefs[WidgetState.TYPE_NAME].orEmpty()
        val emergency = prefs[WidgetState.EMERGENCY].orEmpty()
        val sub = listOf(airline, type).filter { it.isNotBlank() }.joinToString(" · ")

        Header(
            title = prefs[WidgetState.TITLE].orEmpty(),
            sub = if (emergency.isNotBlank()) "$emergency · $sub" else sub,
            badge = prefs[WidgetState.BADGE].orEmpty(),
            logo = prefs[WidgetState.LOGO].orEmpty(),
            age = ageText(prefs),
            onRefresh = refresh(),
            warn = hasProblem(prefs)
        )

        Spacer(GlanceModifier.height(14.dp))

        // The layout never changes shape: anything the route lookup didn't
        // find shows as "-", and the plane sits mid-path with nothing flown.
        val progress = prefs[WidgetState.PROGRESS] ?: -1
        Route(
            from = prefs[WidgetState.FROM_CODE].orEmpty().ifBlank { UNKNOWN },
            fromCity = prefs[WidgetState.FROM_CITY].orEmpty().ifBlank { UNKNOWN },
            to = prefs[WidgetState.TO_CODE].orEmpty().ifBlank { UNKNOWN },
            toCity = prefs[WidgetState.TO_CITY].orEmpty().ifBlank { UNKNOWN },
            progress = progress
        )

        Spacer(GlanceModifier.height(14.dp))
        Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Ink.rule)) {}
        Spacer(GlanceModifier.height(10.dp))

        val stats = listOf(prefs[WidgetState.POSITION].orEmpty(), prefs[WidgetState.DETAIL].orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · ")
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = stats,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(fontSize = 12.sp, color = ColorProvider(Ink.muted))
            )
            val eta = prefs[WidgetState.ETA].orEmpty()
            if (eta.isNotBlank()) {
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = eta,
                    maxLines = 1,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ColorProvider(Ink.ink))
                )
            }
        }
    }

    @Composable
    private fun Header(
        title: String,
        sub: String,
        badge: String,
        age: String,
        onRefresh: Action?,
        warn: Boolean = false,
        logo: String = ""
    ) {
        val logoBitmap = AirlineLogos.bitmap(LocalContext.current, logo)
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            if (logoBitmap != null) {
                Image(
                    provider = ImageProvider(logoBitmap),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.size(36.dp).cornerRadius(10.dp)
                )
            } else Box(
                modifier = GlanceModifier.size(36.dp).background(Ink.badge).cornerRadius(10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (badge.isNotBlank()) {
                    Text(
                        text = badge,
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorProvider(Ink.ink))
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_plane),
                        contentDescription = null,
                        modifier = GlanceModifier.size(18.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(Ink.muted))
                    )
                }
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                DisplayText(title, 17f, Ink.ink)
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        maxLines = 1,
                        style = TextStyle(fontSize = 12.sp, color = ColorProvider(Ink.muted))
                    )
                }
            }
            if (age.isNotBlank()) {
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = age,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
                        color = ColorProvider(if (warn) Ink.warn else Ink.muted)
                    )
                )
            }
            if (onRefresh != null) {
                Spacer(GlanceModifier.width(8.dp))
                // Its own tap target inside a tappable tile: the inner clickable
                // wins, so this refreshes in place instead of opening the detail.
                Box(
                    modifier = GlanceModifier.size(44.dp).background(Ink.badge).cornerRadius(22.dp).clickable(onRefresh),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_refresh),
                        contentDescription = "Find the plane overhead",
                        modifier = GlanceModifier.size(18.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(Ink.muted))
                    )
                }
            }
        }
    }

    @Composable
    private fun Route(from: String, fromCity: String, to: String, toCity: String, progress: Int) {
        val context = LocalContext.current
        val tileWidth = LocalSize.current.width.value
        val pathWidth = (tileWidth - sidePad * 2 - codeWidth * 2 - gap * 2).coerceAtLeast(40f)

        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Column(modifier = GlanceModifier.width(codeWidth.dp)) {
                Code(from, TextAlign.Start)
                City(fromCity, TextAlign.Start)
            }
            Spacer(GlanceModifier.width(gap.dp))
            Image(
                provider = ImageProvider(pathBitmap(context, pathWidth, progress)),
                contentDescription = if (progress >= 0) "$progress% of the way from $from to $to" else "Route unknown",
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.size(pathWidth.dp, 24.dp)
            )
            Spacer(GlanceModifier.width(gap.dp))
            Column(
                modifier = GlanceModifier.width(codeWidth.dp),
                horizontalAlignment = Alignment.Horizontal.End
            ) {
                Code(to, TextAlign.End)
                City(toCity, TextAlign.End)
            }
        }
    }

    @Composable
    private fun Code(code: String, align: TextAlign) {
        // Alignment comes from the column: start for the origin, end for the destination.
        DisplayText(code, 28f, Ink.ink, maxWidthDp = codeWidth.toFloat())
    }

    /**
     * Space Grotesk text. Widgets can only use the phone's built-in fonts, so
     * the callsign and airport codes are drawn as crisp pictures in the app's
     * own font instead, sized exactly to the text.
     */
    @Composable
    private fun DisplayText(text: String, sizeSp: Float, color: Color, maxWidthDp: Float? = null) {
        if (text.isBlank()) return
        val context = LocalContext.current
        val metrics = context.resources.displayMetrics
        val px = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = displayFace(context)
            textSize = px
            this.color = color.toArgb()
        }
        val fm = paint.fontMetrics
        var w = kotlin.math.ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
        val h = kotlin.math.ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        // Too wide for its space: shrink to fit rather than clip.
        maxWidthDp?.let { maxDp ->
            val maxPx = (maxDp * metrics.density).toInt()
            if (w > maxPx) {
                paint.textSize = px * maxPx / w
                w = maxPx
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fm2 = paint.fontMetrics
        Canvas(bmp).drawText(text, 0f, (h - (fm2.descent - fm2.ascent)) / 2f - fm2.ascent, paint)
        Image(
            provider = ImageProvider(bmp),
            contentDescription = text,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.size((w / metrics.density).dp, (h / metrics.density).dp)
        )
    }

    private fun displayFace(context: Context): android.graphics.Typeface =
        face ?: (runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk_bold) }.getOrNull()
            ?: android.graphics.Typeface.DEFAULT_BOLD).also { face = it }

    @Composable
    private fun City(city: String, align: TextAlign) {
        if (city.isBlank()) return
        Text(
            text = city,
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth(),
            style = TextStyle(fontSize = 11.sp, color = ColorProvider(Ink.muted), textAlign = align)
        )
    }

    /**
     * Glance can't place one element over another at a percentage, so the path
     * is drawn as a picture: dashed track, solid line behind the plane, plane
     * at the flight's progress.
     */
    private fun pathBitmap(context: Context, widthDp: Float, progress: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (widthDp * d).toInt().coerceAtLeast(1)
        val h = (24 * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cy = h / 2f
        val planeSize = 22 * d
        val known = progress >= 0
        val x = (if (known) w * progress.coerceIn(0, 100) / 100f else w / 2f)
            .coerceIn(planeSize / 2, w - planeSize / 2)

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ink.track.toArgb()
            strokeWidth = 2 * d
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
        }
        canvas.drawLine(0f, cy, w.toFloat(), cy, track)

        val done = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ink.accent.toArgb()
            strokeWidth = 2 * d
            style = Paint.Style.STROKE
        }
        if (known) canvas.drawLine(0f, cy, x, cy, done)

        // A card-coloured gap behind the plane so the line doesn't run through it.
        val gapPaint = Paint().apply { color = Ink.card.toArgb() }
        canvas.drawRect(x - planeSize / 2 - 2 * d, 0f, x + planeSize / 2 + 2 * d, h.toFloat(), gapPaint)

        ContextCompat.getDrawable(context, R.drawable.ic_plane)?.mutate()?.let { plane ->
            plane.setTint(Ink.accent.toArgb())
            val left = (x - planeSize / 2).toInt()
            val top = ((h - planeSize) / 2).toInt()
            plane.setBounds(left, top, left + planeSize.toInt(), top + planeSize.toInt())
            plane.draw(canvas)
        }
        return bmp
    }

    private fun hasProblem(prefs: Preferences): Boolean {
        val note = prefs[WidgetState.STALE_NOTE].orEmpty()
        return note.isNotBlank() && note != REFRESHING
    }

    /** "2 min ago", "refreshing…", or "offline · 2 min ago" when the last try failed. */
    private fun ageText(prefs: Preferences): String {
        val note = prefs[WidgetState.STALE_NOTE].orEmpty()
        if (note == REFRESHING) return note
        val label = when (note) {
            "offline" -> "No signal"
            "no position" -> "No location"
            else -> note
        }
        val age = ageLabel(prefs[WidgetState.UPDATED_AT] ?: 0L)
        return listOf(label, age).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun ageLabel(updatedAt: Long): String {
        if (updatedAt == 0L) return ""
        val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - updatedAt)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} hr ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }

    companion object {
        @Volatile private var face: android.graphics.Typeface? = null
        const val REFRESHING = "refreshing…"
        const val UNKNOWN = "-"
    }
}

/** The refresh button: queue a fetch, and say so while it runs. */
class PlaneRefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // The fetch takes seconds and nothing on the tile moves until it lands,
        // so without this the button reads as broken and gets tapped again.
        // The worker overwrites this note on both its success and failure paths.
        runCatching {
            updateAppWidgetState(context, glanceId) {
                it[WidgetState.STALE_NOTE] = NearestPlaneWidget.REFRESHING
            }
            NearestPlaneWidget().update(context, glanceId)
        }
        RefreshWorker.refreshNow(context)
    }
}
