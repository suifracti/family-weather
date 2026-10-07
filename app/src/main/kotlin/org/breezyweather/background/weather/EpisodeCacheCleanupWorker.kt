/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.background.weather

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.breezyweather.common.extensions.workManager
import org.breezyweather.common.utils.helpers.LogHelper
import org.breezyweather.domain.subtitle.local.EpisodePreparationCoordinator
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Best-effort local cache cleanup; it never fetches or prepares an episode. */
class EpisodeCacheCleanupWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            EpisodePreparationCoordinator.get(applicationContext).cleanupExpired()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogHelper.log(msg = "Episode cache cleanup failed: ${error.message}")
            // A later app entry or daily run can try again without a retry loop.
            Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "EpisodeCacheCleanup"

        fun schedule(context: Context) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val nextDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            val initialDelayMs = Duration.between(now.toInstant(), nextDay.toInstant()).toMillis()
                .coerceAtLeast(1L)
            val request = PeriodicWorkRequestBuilder<EpisodeCacheCleanupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .build()
            context.workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
