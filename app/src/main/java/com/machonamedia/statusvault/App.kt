package com.machonamedia.statusvault

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class App : Application(), ImageLoaderFactory, Application.ActivityLifecycleCallbacks {

    companion object {
        /** True once the PIN / fingerprint has been accepted for this visit. */
        @JvmField
        var unlocked: Boolean = false

        /** Set before opening a system screen (folder picker, share sheet) so we don't re-lock. */
        @JvmField
        var skipLock: Boolean = false
    }

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        registerActivityLifecycleCallbacks(this)
        if (Prefs.autoSave(this)) AutoSaveWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        if (activity !is LockActivity && !unlocked && Prefs.lockEnabled(this)) {
            activity.startActivity(Intent(activity, LockActivity::class.java))
            activity.overridePendingTransition(0, 0)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started--
        if (started <= 0 && !activity.isChangingConfigurations) {
            started = 0
            if (skipLock) skipLock = false else unlocked = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
