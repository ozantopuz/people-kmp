package com.example.people

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.people.lifecycle.AppLifecycleManager
import org.koin.mp.KoinPlatformTools

class PeopleApplication : Application(), Application.ActivityLifecycleCallbacks {

    private var startedActivityCount = 0
    private var pendingForeground = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        AndroidKoinReadyRegistry.register {
            checkAndNotifyForeground()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivityCount == 0) {
            pendingForeground = true
        }
        startedActivityCount++
        checkAndNotifyForeground()
    }

    override fun onActivityResumed(activity: Activity) {
        checkAndNotifyForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0) {
            pendingForeground = false
            getAppLifecycleManager()?.onBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    internal fun checkAndNotifyForeground() {
        if (startedActivityCount > 0 && pendingForeground) {
            val manager = getAppLifecycleManager()
            if (manager != null) {
                manager.onForeground()
                pendingForeground = false
            }
        }
    }

    private fun getAppLifecycleManager(): AppLifecycleManager? {
        return KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<AppLifecycleManager>()
    }
}
