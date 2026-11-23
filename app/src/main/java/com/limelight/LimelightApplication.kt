package com.limelight

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ScrollView

import com.google.firebase.FirebaseApp
import com.limelight.utils.AppTheme
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.crash.CrashReporter
import com.limelight.utils.ConfigurationSyncScheduler

class LimelightApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerWatchUiCallbacks()
        AppTheme.applyStoredAppTheme(this)
        initializeFirebaseSafely()
        CrashReporter.install(this)
        ConfigurationSyncScheduler.runNow(this)
        warmUpClientCertificate()
    }

    private fun isWatchScreen(dm: DisplayMetrics, config: Configuration): Boolean {
        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        val isRound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && config.isScreenRound
        return isRound || (minPx in 1..520)
    }

    private fun applyWatchDensity(activity: Activity) {
        if (activity.javaClass.simpleName == "Game") return
        val dm = activity.resources.displayMetrics
        val config = activity.resources.configuration
        if (!isWatchScreen(dm, config)) return
        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        if (minPx <= 0) return

        // 466x466 圆形手表按 385dp 等效逻辑宽度缩放，使按钮大小适配且不挤压底部图标
        val targetDensity = (minPx / 385f).coerceIn(0.75f, 2.5f)
        val targetDpi = (targetDensity * DisplayMetrics.DENSITY_DEFAULT).toInt()
        val fontScale = config.fontScale.takeIf { it > 0f } ?: 1.0f

        dm.density = targetDensity
        dm.densityDpi = targetDpi
        @Suppress("DEPRECATION")
        dm.scaledDensity = targetDensity * fontScale
        config.densityDpi = targetDpi
    }

    private fun registerWatchUiCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyWatchDensity(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyWatchDensity(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                applyWatchDensity(activity)
                applyRoundSafePadding(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                applyWatchDensity(activity)
                applyRoundSafePadding(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyRoundSafePadding(activity: Activity) {
        if (activity.javaClass.simpleName == "Game") return
        val dm = activity.resources.displayMetrics
        val config = activity.resources.configuration
        if (!isWatchScreen(dm, config)) return

        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        val hPad = (minPx * 0.07f).toInt()
        val vPad = (minPx * 0.08f).toInt()

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.post {
            // 不对 content 本身加 padding，确保背景全屏铺满圆形表盘不被切成矩形
            if (content.paddingLeft != 0 || content.paddingTop != 0 ||
                content.paddingRight != 0 || content.paddingBottom != 0
            ) {
                content.setPadding(0, 0, 0, 0)
            }
            for (i in 0 until content.childCount) {
                applyInsetsPreservingBackground(content.getChildAt(i), hPad, vPad, isRootChild = true)
            }
        }
    }

    private fun applyInsetsPreservingBackground(
        view: View,
        hPad: Int,
        vPad: Int,
        isRootChild: Boolean
    ) {
        val className = view.javaClass.name
        // ComposeView 内部自带布局与背景，保持全屏铺满不裁切背景
        if (className.contains("ComposeView")) return

        val isScrollable = view is ScrollView ||
            view is AbsListView ||
            className.contains("RecyclerView") ||
            className.contains("NestedScrollView")

        if (isScrollable && view is ViewGroup) {
            view.clipToPadding = false
            val newLeft = maxOf(view.paddingLeft, hPad)
            val newTop = maxOf(view.paddingTop, vPad)
            val newRight = maxOf(view.paddingRight, hPad)
            val newBottom = maxOf(view.paddingBottom, vPad)
            if (view.paddingLeft != newLeft || view.paddingTop != newTop ||
                view.paddingRight != newRight || view.paddingBottom != newBottom
            ) {
                view.setPadding(newLeft, newTop, newRight, newBottom)
            }
            return
        }

        if (view is ViewGroup) {
            if (isRootChild && !hasScrollableDescendant(view)) {
                view.clipToPadding = false
                val newLeft = maxOf(view.paddingLeft, hPad)
                val newTop = maxOf(view.paddingTop, vPad)
                val newRight = maxOf(view.paddingRight, hPad)
                val newBottom = maxOf(view.paddingBottom, vPad)
                if (view.paddingLeft != newLeft || view.paddingTop != newTop ||
                    view.paddingRight != newRight || view.paddingBottom != newBottom
                ) {
                    view.setPadding(newLeft, newTop, newRight, newBottom)
                }
            } else {
                for (i in 0 until view.childCount) {
                    applyInsetsPreservingBackground(view.getChildAt(i), hPad, vPad, isRootChild = false)
                }
            }
        }
    }

    private fun hasScrollableDescendant(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = child.javaClass.name
            if (child is ScrollView || child is AbsListView ||
                name.contains("RecyclerView") || name.contains("NestedScrollView")
            ) {
                return true
            }
            if (child is ViewGroup && !name.contains("ComposeView") && hasScrollableDescendant(child)) {
                return true
            }
        }
        return false
    }

    private fun initializeFirebaseSafely() {
        try {
            FirebaseApp.initializeApp(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Firebase init skipped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun warmUpClientCertificate() {
        Thread({
            try {
                AndroidCryptoProvider(this).getClientCertificate()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }, "cert-warmup").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    companion object {
        private const val TAG = "LimelightApp"
    }
}
