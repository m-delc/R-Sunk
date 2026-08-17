package com.rsunk.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Compatibility stub for periodic jobs created by R-Sunk versions before 1.4.2.
 * Automatic transfers were removed in 1.4.2. This worker intentionally performs
 * no file operation, so a stale queued job can never move or copy anything.
 */
class SortWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    override fun doWork(): Result = Result.success()
}
