package com.galaxy.starshine8.wallpaper.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.galaxy.starshine8.wallpaper.core.render.StarshineGlSceneRenderer
import com.galaxy.starshine8.wallpaper.core.storage.WallpaperQuotaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 宿主 1：系统级动态壁纸服务（StarshineWallpaperService）。
 * P1 加固特性：
 * 1. 注册包内 ACTION_WALLPAPER_BUNDLE_UPDATED 实时同步广播，工作台切图/改风格后桌面壁纸立即热重载。
 * 2. 完整转发 onConfigurationChanged(newConfig) 至所有活跃 Engine 的 CockpitSafetyMonitor。
 * 3. 在 onVisibilityChanged(false) 与 onSurfaceDestroyed() 中 100% 挂起渲染循环，保障导航 0% GPU 争抢。
 */
class StarshineWallpaperService : WallpaperService() {

    companion object {
        const val ACTION_WALLPAPER_BUNDLE_UPDATED = "com.galaxy.starshine8.wallpaper.ACTION_BUNDLE_UPDATED"
    }

    private val activeEngines = mutableSetOf<StarshineGlWallpaperEngine>()

    override fun onCreateEngine(): Engine {
        return StarshineGlWallpaperEngine().also { activeEngines.add(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        activeEngines.forEach { it.notifyConfigurationChanged(newConfig) }
    }

    inner class StarshineGlWallpaperEngine : Engine() {

        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var glSurfaceView: WallpaperGlSurfaceView? = null
        private var sceneRenderer: StarshineGlSceneRenderer? = null
        private var safetyMonitor: CockpitSafetyMonitor? = null
        private var quotaManager: WallpaperQuotaManager? = null
        private var isBundleReceiverRegistered = false

        private val bundleUpdatedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_WALLPAPER_BUNDLE_UPDATED) {
                    reloadActiveBundleAsync()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            val renderer = StarshineGlSceneRenderer(applicationContext)
            val glView = WallpaperGlSurfaceView(applicationContext).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            val monitor = CockpitSafetyMonitor(
                context = applicationContext,
                coroutineScope = engineScope,
                renderer = renderer
            )
            val storage = WallpaperQuotaManager(applicationContext)

            sceneRenderer = renderer
            glSurfaceView = glView
            safetyMonitor = monitor
            quotaManager = storage

            ContextCompat.registerReceiver(
                applicationContext,
                bundleUpdatedReceiver,
                IntentFilter(ACTION_WALLPAPER_BUNDLE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isBundleReceiverRegistered = true

            reloadActiveBundleAsync()
        }

        fun notifyConfigurationChanged(newConfig: Configuration) {
            safetyMonitor?.onConfigurationChanged(newConfig)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val renderer = sceneRenderer ?: return
            val glView = glSurfaceView ?: return

            if (visible) {
                renderer.setRenderPaused(false)
                glView.onResume()
                glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                safetyMonitor?.startMonitoring()
                reloadActiveBundleAsync()
            } else {
                renderer.setRenderPaused(true)
                safetyMonitor?.stopMonitoring()
                glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                glView.onPause()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            sceneRenderer?.setRenderPaused(true)
            safetyMonitor?.stopMonitoring()
            glSurfaceView?.onPause()
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event != null && (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE)) {
                val w = glSurfaceView?.width?.coerceAtLeast(1) ?: 2560
                val h = glSurfaceView?.height?.coerceAtLeast(1) ?: 1600
                sceneRenderer?.notifyTouchRipple(
                    normalizedX = event.x / w.toFloat(),
                    normalizedY = event.y / h.toFloat()
                )
            }
        }

        override fun onDestroy() {
            activeEngines.remove(this)
            if (isBundleReceiverRegistered) {
                runCatching { applicationContext.unregisterReceiver(bundleUpdatedReceiver) }
                isBundleReceiverRegistered = false
            }
            safetyMonitor?.stopMonitoring()
            sceneRenderer?.setRenderPaused(true)
            glSurfaceView?.queueEvent {
                sceneRenderer?.release()
            }
            glSurfaceView?.onDestroyWallpaperSurface()
            engineScope.cancel()
            super.onDestroy()
        }

        private fun reloadActiveBundleAsync() {
            val renderer = sceneRenderer ?: return
            val storage = quotaManager ?: return
            engineScope.launch(Dispatchers.IO) {
                storage.loadActiveOrDefaultWallpaperBundle()?.let { bundle ->
                    renderer.bindWallpaperBundle(bundle)
                }
            }
        }

        inner class WallpaperGlSurfaceView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder {
                return this@StarshineGlWallpaperEngine.surfaceHolder
            }

            fun onDestroyWallpaperSurface() {
                super.onDetachedFromWindow()
            }
        }
    }
}
