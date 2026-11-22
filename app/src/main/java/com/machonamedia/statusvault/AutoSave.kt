package com.machonamedia.statusvault

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object AutoSaver {
    /** Saves every status not seen before. Returns how many were saved. */
    @Synchronized
    fun run(c: Context): Int {
        val handled = HashSet(Prefs.handled(c))
        val savedNames = Saver.listSaved(c).map { it.name }.toSet()
        val present = HashSet<String>()
        var count = 0
        var listedAny = false
        for (s in Source.values()) {
            val items = StatusRepo.list(c, s) ?: continue
            listedAny = true
            for (m in items) {
                present.add(m.name)
                if (m.name in handled) continue
                if (m.name in savedNames) {
                    handled.add(m.name)
                } else if (Saver.save(c, m)) {
                    handled.add(m.name)
                    count++
                }
            }
        }
        // Forget statuses that have expired so the list cannot grow forever.
        if (listedAny) handled.retainAll(present)
        Prefs.setHandled(c, handled)
        return count
    }
}

class AutoSaveWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        if (Prefs.autoSave(applicationContext)) AutoSaver.run(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "autosave"

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<AutoSaveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(c: Context) {
            WorkManager.getInstance(c).cancelUniqueWork(NAME)
        }
    }
}
