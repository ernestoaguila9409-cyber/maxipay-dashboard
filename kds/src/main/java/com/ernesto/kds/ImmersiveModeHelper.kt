package com.ernesto.kds

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

object ImmersiveModeHelper {

    private val windowFocusListeners =
        WeakHashMap<Activity, ViewTreeObserver.OnWindowFocusChangeListener>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                    applyImmersive(activity)
                    val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                        if (hasFocus) applyImmersive(activity)
                    }
                    activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                    windowFocusListeners[activity] = listener
                }

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {
                    applyImmersive(activity)
                }

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {
                    windowFocusListeners.remove(activity)?.let { listener ->
                        val vto = activity.window.decorView.viewTreeObserver
                        if (vto.isAlive) {
                            vto.removeOnWindowFocusChangeListener(listener)
                        }
                    }
                }
            },
        )
    }

    fun applyImmersive(activity: Activity) {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
