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
        applyWatchDensity(resources.displayMetrics, resources.configuration)
        registerWatchUiCallbacks()
        AppTheme.applyStoredAppTheme(this)
        initializeFirebaseSafely()
        CrashReporter.install(this)
        ConfigurationSyncScheduler.runNow(this)
        warmUpClientCertificate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWatchDensity(resources.displayMetrics, resources.configuration)
    }

    private fun isWatchScreen(dm: DisplayMetrics, config: Configuration): Boolean {
        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        val isRound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && config.isScreenRound
        // 兼容圆屏标记以及 466x466 等小屏分辨率，确保手表安装后自动生效
        return isRound || (minPx in 1..520)
    }

    private fun applyWatchDensity(dm: DisplayMetrics, config: Configuration) {
        if (!isWatchScreen(dm, config)) return
        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        if (minPx <= 0) return

        // 466x466 圆形手表按 348dp 等效逻辑宽度缩放，使按钮与弹窗整体缩小适配表盘
        val targetDensity = (minPx / 348f).coerceIn(0.75f, 2.5f)
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
                applyWatchDensity(activity.resources.displayMetrics, activity.resources.configuration)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyWatchDensity(activity.resources.displayMetrics, activity.resources.configuration)
            }

            override fun onActivityStarted(activity: Activity) {
                applyWatchDensity(activity.resources.displayMetrics, activity.resources.configuration)
                applyRoundSafePadding(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                applyWatchDensity(activity.resources.displayMetrics, activity.resources.configuration)
                applyRoundSafePadding(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyRoundSafePadding(activity: Activity) {
        // 串流画面 Activity (Game) 需要全屏铺满，不加圆屏内边距
        if (activity.javaClass.simpleName == "Game") return
        val dm = activity.resources.displayMetrics
        val config = activity.resources.configuration
        if (!isWatchScreen(dm, config)) return

        // 针对 466x466 圆形表盘：左右留 9% (~42px)、上下留 11% (~51px) 安全区，防止四角按钮被圆弧裁切
        val minPx = minOf(dm.widthPixels, dm.heightPixels)
        val hPad = (minPx * 0.09f).toInt()
        val vPad = (minPx * 0.11f).toInt()

        val content = activity.findViewById<View>(android.R.id.content) ?: return
        content.post {
            if (content.paddingLeft != hPad || content.paddingTop != vPad ||
                content.paddingRight != hPad || content.paddingBottom != vPad
            ) {
                content.setPadding(hPad, vPad, hPad, vPad)
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
