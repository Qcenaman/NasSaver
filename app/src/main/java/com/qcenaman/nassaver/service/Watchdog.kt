package com.qcenaman.nassaver.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * A second way back after a restart: a persisted job that Android runs every 15 minutes, even
 * after reboots, and that starts the server if it isn't running.
 */
class Watchdog : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (KeepAliveService.current == null) KeepAliveService.start(this, "watchdog found it stopped")
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 8080
        private const val INTERVAL_MS = 15 * 60_000L

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, Watchdog::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }
                .onFailure { Logs.get(ctx).add("watchdog not scheduled: ${it.javaClass.simpleName} ${it.message}") }
        }
    }
}
