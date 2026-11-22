package com.limelight

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View

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
        // 串流页面 (Game) 保持原版原生密度与全屏显示，避免影响视频解码与 Surface 渲染导致黑屏
        if (activity.javaClass.simpleName == "Game") return
        val dm = activity.resources.displayMetrics
        val config = activity.resources.configuration
        if (!isWatchScreen(dm, config)) return
        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        if (minPx <= 0) return

        // 466x466 圆形手表按 380dp 等效逻辑宽度缩放，保留更多纵向空间显示底部电脑图标
        val targetDensity = (minPx / 380f).coerceIn(0.75f, 2.5f)
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

        // 466x466 圆形表盘：四周留 6.5% (~30px) 紧凑安全区，兼顾防裁切与底部图标完整显示
        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        val pad = (minPx * 0.065f).toInt()

        val content = activity.findViewById<View>(android.R.id.content) ?: return
        content.post {
            if (content.paddingLeft != pad || content.paddingTop != pad ||
                content.paddingRight != pad || content.paddingBottom != pad
            ) {
                content.setPadding(pad, pad, pad, pad)
            }
        }
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
