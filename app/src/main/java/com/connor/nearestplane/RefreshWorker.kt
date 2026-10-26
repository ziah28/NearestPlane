package com.connor.nearestplane

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        Diagnostics.noteRun(context, "plane")

        // Readiness, not permission: a pinned place needs neither.
        if (!LocationSource.isReady(context)) {
            write(context) { it[WidgetState.STATUS] = "no_permission" }
            return Result.success()
        }

        // Background work has no console and no crash dialog, so an exception
        // escaping from here would be invisible. Anything unexpected below is
        // caught, recorded and degraded to a stale tile.
        return try {
            refresh(context)
        } catch (e: Exception) {
            degrade(context, "offline")
            Diagnostics.noteError(context, "plane", e.message?.take(90) ?: e.javaClass.simpleName)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private suspend fun refresh(context: Context): Result {
        // Every plane refresh is now one you asked for, so it gets a fresh fix.
        val fix = LocationSource.current(context, fresh = true)
        if (fix == null) {
            degrade(context, "no position")
            Diagnostics.noteError(context, "plane", "No position available")
            // Bounded like every other failure here: an unbounded retry on a
            // phone with location off is a job that never finishes trying.
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        Diagnostics.notePosition(context, "plane", fix.describe)

        val filter = AppSettings.planeFilter(context)

        // Only the aircraft fetch can fail the job. Everything after it is
        // enrichment, and enrichment failing must never cost you the tile.
        val plane = try {
            AdsbClient.nearest(
                lat = fix.lat,
                lon = fix.lon,
                radiusNm = filter.radius.nm,
                maxAltitudeFt = filter.ceiling.maxFt,
                station = AppSettings.station(context),
                onStationError = { Diagnostics.noteError(context, "plane", "station: ${it.take(80)}") }
            )
        } catch (e: Exception) {
            val msg = e.message?.take(90) ?: e.javaClass.simpleName
            degrade(context, "offline")
            Diagnostics.noteError(context, "plane", msg)
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }

        if (plane == null) {
            write(context) {
                it[WidgetState.STATUS] = "empty"
                it[WidgetState.TITLE] = "Clear skies"
                it[WidgetState.AIRLINE] = ""
                it[WidgetState.TYPE_NAME] = ""
                it[WidgetState.ROUTE] = ""
                it[WidgetState.TIMING] = ""
                it[WidgetState.POSITION] = buildString {
                    append("Nothing within ${filter.radius.nm} nm")
                    filter.ceiling.maxFt?.let { append(" ${filter.ceiling.label.lowercase()}") }
                }
                it[WidgetState.DETAIL] = ""
                it[WidgetState.EMERGENCY] = ""
                it[WidgetState.STALE_NOTE] = fix.staleNote.orEmpty()
                it[WidgetState.PINNED] = fix.source == PositionSource.FIXED
                it[WidgetState.UPDATED_AT] = System.currentTimeMillis()
                it[WidgetState.BADGE] = ""
                it[WidgetState.FROM_CODE] = ""
                it[WidgetState.FROM_CITY] = ""
                it[WidgetState.TO_CODE] = ""
                it[WidgetState.TO_CITY] = ""
                it[WidgetState.PROGRESS] = -1
                it[WidgetState.ETA] = ""
                it[WidgetState.LOGO] = ""
                it[WidgetState.HEX] = ""
                it[WidgetState.DEP_TIME] = ""
                it[WidgetState.ARR_TIME] = ""
            }
            Diagnostics.noteOk(context, "plane")
            return Result.success()
        }

        // Shared with the detail screen, so the two can't disagree about a route.
        val report = PlaneSummary.build(context, plane)
        report.enrichError?.let { Diagnostics.noteError(context, "plane", "enrich: $it") }

        showPlane(context, plane, report, fix)
        Diagnostics.noteOk(context, "plane")
        return Result.success()
    }

    /**
     * A failed refresh never blanks a working tile, and never latches.
     *
     * Previously a failure set STATUS to "error", which swapped the whole tile
     * for an error screen. Now, if there's usable data, only the corner note
     * changes and STATUS stays "ok" — so the next success clears it by itself.
     * The error screen is reserved for a tile that has never had data at all.
     */
    private suspend fun degrade(context: Context, note: String) {
        write(context) { p ->
            val hasData = !p[WidgetState.TITLE].isNullOrBlank()
            if (hasData) {
                p[WidgetState.STATUS] = "ok"
                p[WidgetState.STALE_NOTE] = note
            } else {
                p[WidgetState.STATUS] = "error"
                p[WidgetState.STALE_NOTE] = note
            }
        }
    }

    private suspend fun write(context: Context, block: (MutablePreferences) -> Unit) {
        runCatching {
            val manager = GlanceAppWidgetManager(context)
            val widget = NearestPlaneWidget()
            manager.getGlanceIds(NearestPlaneWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { block(it) }
                widget.update(context, id)
            }
        }
    }

    companion object {

        /**
         * Puts a plane on every placed tile. The refresh button and the detail
         * screen's refresh both come through here, so the two never disagree.
         */
        internal suspend fun showPlane(context: Context, plane: Aircraft, report: PlaneReport, fix: Position) {
            // A missing logo never costs the tile: no logo just means the code badge.
            val logo = report.route?.airlineIata
                ?.let { code -> if (runCatching { AirlineLogos.ensure(context, code) }.getOrDefault(false)) code else "" }
                .orEmpty()
            val pass = BoardingPass.from(plane, report)
            // Saved times cost nothing; a new flight costs one lookup. Never fails the tile.
            val times = runCatching { FlightTimes.forPlane(context, plane) }.getOrNull()

            writeTiles(context) { p ->
                p[WidgetState.STATUS] = "ok"
                p[WidgetState.TITLE] = plane.label
                p[WidgetState.AIRLINE] = report.operator
                p[WidgetState.TYPE_NAME] = report.typeName.orEmpty()
                p[WidgetState.ROUTE] = report.routeLine
                p[WidgetState.TIMING] = report.timingLine
                p[WidgetState.POSITION] = plane.positionLine
                p[WidgetState.DETAIL] = plane.detail
                p[WidgetState.EMERGENCY] = plane.emergency.orEmpty()
                // An old position is worth saying out loud: the distance and bearing
                // are measured from it, and nothing else on the tile hints at that.
                p[WidgetState.STALE_NOTE] = fix.staleNote.orEmpty()
                p[WidgetState.PINNED] = fix.source == PositionSource.FIXED
                p[WidgetState.UPDATED_AT] = System.currentTimeMillis()
                p[WidgetState.HEX] = plane.hex
                p[WidgetState.BADGE] = pass.badge
                p[WidgetState.FROM_CODE] = pass.fromCode
                p[WidgetState.FROM_CITY] = pass.fromCity
                p[WidgetState.TO_CODE] = pass.toCode
                p[WidgetState.TO_CITY] = pass.toCity
                p[WidgetState.PROGRESS] = pass.progress
                p[WidgetState.ETA] = pass.eta
                p[WidgetState.LOGO] = logo
                writeTime(p, times?.departure, WidgetState.DEP_TIME, WidgetState.DEP_SCHED, WidgetState.DEP_PILL, WidgetState.DEP_LATE, WidgetState.DEP_DONE)
                writeTime(p, times?.arrival, WidgetState.ARR_TIME, WidgetState.ARR_SCHED, WidgetState.ARR_PILL, WidgetState.ARR_LATE, WidgetState.ARR_DONE)
            }
        }

        private fun writeTime(
            p: MutablePreferences,
            t: FlightTime?,
            time: androidx.datastore.preferences.core.Preferences.Key<String>,
            sched: androidx.datastore.preferences.core.Preferences.Key<String>,
            pill: androidx.datastore.preferences.core.Preferences.Key<String>,
            late: androidx.datastore.preferences.core.Preferences.Key<Int>,
            done: androidx.datastore.preferences.core.Preferences.Key<Boolean>
        ) {
            p[time] = t?.shown.orEmpty()
            p[sched] = t?.scheduled.orEmpty()
            p[pill] = t?.pill.orEmpty()
            p[late] = if (t == null || t.delayMin == null) -1 else if (t.late) 1 else 0
            p[done] = t?.actual ?: false
        }

        internal suspend fun writeTiles(context: Context, block: (MutablePreferences) -> Unit) {
            runCatching {
                val manager = GlanceAppWidgetManager(context)
                val widget = NearestPlaneWidget()
                manager.getGlanceIds(NearestPlaneWidget::class.java).forEach { id ->
                    updateAppWidgetState(context, id) { block(it) }
                    widget.update(context, id)
                }
            }
        }

        private const val PERIODIC_NAME = "nearest-plane-periodic"
        private const val ONE_SHOT_NAME = "nearest-plane-now"

        /**
         * Declared by the refresh-now job as much as the periodic one. The
         * refresh-now job is also queued from the background: the widget's
         * refresh button, and its updatePeriodMillis backup. Android only
         * guarantees a background job the network when it declares that it
         * needs it, so without this the fetch failed with "Unable to resolve
         * host" and the tile said offline, while the same refresh from the
         * open app worked.
         */
        private val ONLINE = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * UPDATE, not KEEP. With KEEP, the job enqueued by an older build lives
         * forever and every change made here is silently ignored — including
         * this one, until the app is reinstalled.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(ONLINE)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Overhead refreshes only when you tap the button, so any periodic job
         * an earlier build scheduled is removed. schedulePeriodic stays for
         * reference but nothing calls it.
         */
        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                oneShot()
            )
        }

        internal fun oneShot(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(ONLINE)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
    }
}
