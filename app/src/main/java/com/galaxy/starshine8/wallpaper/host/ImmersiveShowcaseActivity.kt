package com.galaxy.starshine8.wallpaper.host

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.galaxy.starshine8.wallpaper.core.contract.CockpitWorkbenchUiState
import com.galaxy.starshine8.wallpaper.core.contract.ComputeNodeEndpoint
import com.galaxy.starshine8.wallpaper.core.contract.LoadedWallpaperBundle
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperManifest
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperPaletteColors
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperStyleConfig
import com.galaxy.starshine8.wallpaper.core.pipeline.SafeImageNormalizer
import com.galaxy.starshine8.wallpaper.core.pipeline.SerialDepthInferenceEngine
import com.galaxy.starshine8.wallpaper.core.render.StarshineGlSceneRenderer
import com.galaxy.starshine8.wallpaper.core.storage.WallpaperQuotaManager
import com.galaxy.starshine8.wallpaper.feature.phaseb.LanExternalComputeAdapter
import com.galaxy.starshine8.wallpaper.feature.transfer.LanFastTransferServer
import com.galaxy.starshine8.wallpaper.ui.showcase.StandByOverlayLayer
import com.galaxy.starshine8.wallpaper.ui.theme.StarshineCockpitTheme
import com.galaxy.starshine8.wallpaper.ui.workbench.CockpitWorkbenchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

typealias ShowcaseActivity = ImmersiveShowcaseActivity

/**
 * 宿主 2：全屏沉浸式驻车画报与 15.4 寸 2560x1600 工作台宿主（ImmersiveShowcaseActivity / ShowcaseActivity）。
 * P0/P1 修复：
 * 1. 动态申请 RECORD_AUDIO 运行时权限，授权后立即调用 safetyMonitor.restartAudioPulse() 激活 Tier 1 真实频谱。
 * 2. 上传新壁纸、切换壁纸或调整风格参数后，通过 ACTION_WALLPAPER_BUNDLE_UPDATED 通知桌面壁纸服务实时热重载。
 */
class ImmersiveShowcaseActivity : ComponentActivity() {

    companion object {
        private const val AUTO_HIDE_INACTIVITY_MS = 3_000L
    }

    lateinit var sceneRenderer: StarshineGlSceneRenderer
        private set

    lateinit var safetyMonitor: CockpitSafetyMonitor
        private set

    lateinit var quotaManager: WallpaperQuotaManager
        private set

    lateinit var transferServer: LanFastTransferServer
        private set

    lateinit var externalComputeAdapter: LanExternalComputeAdapter
        private set

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var imageNormalizer: SafeImageNormalizer
    private lateinit var depthEngine: SerialDepthInferenceEngine

    private val _activeBundleFlow = MutableStateFlow<LoadedWallpaperBundle?>(null)
    val activeBundleFlow: StateFlow<LoadedWallpaperBundle?> = _activeBundleFlow.asStateFlow()

    private val _savedManifestsFlow = MutableStateFlow<List<WallpaperManifest>>(emptyList())
    private val _quotaUsedBytesFlow = MutableStateFlow(0L)

    private val _isWallpaperBindingLocked = MutableStateFlow(false)
    val isWallpaperBindingLocked: StateFlow<Boolean> = _isWallpaperBindingLocked.asStateFlow()

    private val _discoveredPhaseBNodes = MutableStateFlow<List<ComputeNodeEndpoint>>(emptyList())
    private val _phaseBStatusText = MutableStateFlow("正在通过 mDNS 扫描局域网 RTX 4060 Ti 算力节点...")

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                safetyMonitor.restartAudioPulse()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdgeImmersiveFullscreen()

        sceneRenderer = StarshineGlSceneRenderer(applicationContext)
        quotaManager = WallpaperQuotaManager(applicationContext)
        imageNormalizer = SafeImageNormalizer(applicationContext)
        depthEngine = SerialDepthInferenceEngine(applicationContext, lifecycleScope)
        externalComputeAdapter = LanExternalComputeAdapter(
            context = applicationContext,
            quotaManager = quotaManager,
            coroutineScope = lifecycleScope
        )

        glSurfaceView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event != null && (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE)) {
                    val w = width.coerceAtLeast(1)
                    val h = height.coerceAtLeast(1)
                    sceneRenderer.notifyTouchRipple(event.x / w.toFloat(), event.y / h.toFloat())
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(sceneRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        safetyMonitor = CockpitSafetyMonitor(
            context = applicationContext,
            coroutineScope = lifecycleScope,
            renderer = sceneRenderer,
            onThermalRenderModeChanged = { shouldUseStaticDirtyMode ->
                glSurfaceView.renderMode = if (shouldUseStaticDirtyMode) {
                    GLSurfaceView.RENDERMODE_WHEN_DIRTY
                } else {
                    GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            }
        )

        transferServer = LanFastTransferServer(
            context = applicationContext,
            coroutineScope = lifecycleScope,
            imageNormalizer = imageNormalizer,
            depthInferenceEngine = depthEngine,
            quotaManager = quotaManager,
            onNewWallpaperReady = { bundle ->
                _activeBundleFlow.value = bundle
                sceneRenderer.bindWallpaperBundle(bundle)
                refreshGalleryInventoryAsync()
                notifyWallpaperServiceBundleUpdated()
            }
        )

        val composeOverlayView = ComposeView(this).apply {
            setContent {
                val activeBundle by activeBundleFlow.collectAsState()
                val savedManifests by _savedManifestsFlow.collectAsState()
                val quotaUsedBytes by _quotaUsedBytesFlow.collectAsState()
                val transferState by transferServer.serverState.collectAsState()
                val bindingLocked by isWallpaperBindingLocked.collectAsState()
                val phaseBNodes by _discoveredPhaseBNodes.collectAsState()
                val phaseBStatus by _phaseBStatusText.collectAsState()

                var isOverlayVisible by remember { mutableStateOf(true) }
                var isDrawerOpen by remember { mutableStateOf(false) }
                var isQrDialogOpen by remember { mutableStateOf(false) }
                var lastInteractionMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                var cockpitSnapshot by remember { mutableStateOf(safetyMonitor.getCurrentStateSnapshot()) }

                LaunchedEffect(Unit) {
                    while (isActive) {
                        cockpitSnapshot = safetyMonitor.getCurrentStateSnapshot()
                        delay(33L)
                    }
                }

                LaunchedEffect(lastInteractionMs, isDrawerOpen, isQrDialogOpen, isOverlayVisible) {
                    if (isOverlayVisible && !isDrawerOpen && !isQrDialogOpen) {
                        delay(AUTO_HIDE_INACTIVITY_MS)
                        if (SystemClock.elapsedRealtime() - lastInteractionMs >= AUTO_HIDE_INACTIVITY_MS) {
                            isOverlayVisible = false
                        }
                    }
                }

                val currentPalette = activeBundle?.manifest?.palette ?: WallpaperPaletteColors()
                val currentStyle = activeBundle?.manifest?.styleConfig ?: WallpaperStyleConfig()
                val currentTitle = activeBundle?.manifest?.title ?: "西湖晨雾·星耀画卷"
                val currentAssetId = activeBundle?.manifest?.assetId ?: "wp_20261015_a8f9c2"
                val isPhaseBReady = activeBundle?.manifest?.phaseBVideo?.isAvailable == true

                val workbenchUiState: CockpitWorkbenchUiState = remember(
                    transferState,
                    safetyMonitor.currentThermalTier,
                    savedManifests.size,
                    currentAssetId,
                    currentTitle
                ) {
                    when {
                        safetyMonitor.currentThermalTier >= 1 -> CockpitWorkbenchUiState.ThermalProtectedMode(
                            thermalTier = safetyMonitor.currentThermalTier,
                            statusDescription = if (safetyMonitor.currentThermalTier == 1) {
                                "中温 30fps 单层粒子节能运行中"
                            } else {
                                "高温静态保护态已开启"
                            }
                        )
                        transferState.lastErrorMessage != null -> CockpitWorkbenchUiState.RecoverableError(
                            userFriendlyMessage = transferState.lastErrorMessage
                        )
                        transferState.stage != LanFastTransferServer.TransferStage.IDLE &&
                            transferState.stage != LanFastTransferServer.TransferStage.COMPLETED ->
                            CockpitWorkbenchUiState.LoadingPipeline(
                                stageLabel = transferState.stage.label,
                                progressPercent = transferState.stage.progressPercent
                            )
                        savedManifests.size <= 1 -> CockpitWorkbenchUiState.EmptyCustomGallery(
                            defaultAssetTitle = currentTitle
                        )
                        else -> CockpitWorkbenchUiState.IdealReady(
                            activeAssetId = currentAssetId,
                            totalSavedCount = savedManifests.size
                        )
                    }
                }

                StarshineCockpitTheme(manifestPalette = currentPalette) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        StandByOverlayLayer(
                            wallpaperTitle = currentTitle,
                            cockpitState = cockpitSnapshot,
                            isOverlayVisible = isOverlayVisible,
                            isWallpaperBindingLocked = bindingLocked,
                            onToggleOverlayVisibility = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                if (isDrawerOpen) {
                                    isDrawerOpen = false
                                } else {
                                    isOverlayVisible = !isOverlayVisible
                                }
                            },
                            onOpenWorkbenchDrawer = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                isDrawerOpen = true
                                refreshGalleryInventoryAsync()
                                discoverPhaseBNodesAsync()
                            },
                            onOpenQrFastTransfer = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                transferServer.refreshNetworkEndpointsAndQrCode()
                                isQrDialogOpen = true
                            }
                        )

                        CockpitWorkbenchScreen(
                            workbenchUiState = workbenchUiState,
                            savedManifests = savedManifests,
                            activeAssetId = currentAssetId,
                            quotaUsedBytes = quotaUsedBytes,
                            currentStyleConfig = currentStyle,
                            transferState = transferState,
                            discoveredPhaseBNodes = phaseBNodes,
                            phaseBStatusText = phaseBStatus,
                            isPhaseBVideoAvailable = isPhaseBReady,
                            isDrawerOpen = isDrawerOpen,
                            isQrDialogOpen = isQrDialogOpen,
                            resolveThumbnailFile = { assetId -> quotaManager.getThumbnailFile(assetId) },
                            onCloseDrawer = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                isDrawerOpen = false
                            },
                            onCloseQrDialog = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                isQrDialogOpen = false
                            },
                            onRetryRecoverableError = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                transferServer.stopServer()
                                transferServer.startServer()
                            },
                            onOpenQrFromEmpty = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                isDrawerOpen = false
                                transferServer.refreshNetworkEndpointsAndQrCode()
                                isQrDialogOpen = true
                            },
                            onSelectSavedWallpaper = { targetAssetId ->
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                lifecycleScope.launch(Dispatchers.IO) {
                                    quotaManager.activateWallpaperById(targetAssetId)?.let { loaded ->
                                        _activeBundleFlow.value = loaded
                                        sceneRenderer.bindWallpaperBundle(loaded)
                                        refreshGalleryInventoryAsync()
                                        notifyWallpaperServiceBundleUpdated()
                                    }
                                }
                            },
                            onTogglePinWallpaper = { targetAssetId ->
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                lifecycleScope.launch(Dispatchers.IO) {
                                    _savedManifestsFlow.value = quotaManager.togglePinStatus(targetAssetId)
                                }
                            },
                            onStyleConfigChangedRealtime = { newConfig ->
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                sceneRenderer.updateStyleConfig(newConfig)
                            },
                            onStyleConfigDebouncedPersist = { newConfig ->
                                updateActiveStyleAndPersist(newConfig)
                            },
                            onTriggerSystemWallpaperBind = {
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                requestSystemLiveWallpaperOrFallback()
                            },
                            onTriggerPhaseBRedraw = { selectedNode ->
                                lastInteractionMs = SystemClock.elapsedRealtime()
                                val bundle = activeBundle ?: return@CockpitWorkbenchScreen
                                externalComputeAdapter.registerManualEndpoint(
                                    nodeName = selectedNode.nodeName,
                                    baseUrl = selectedNode.baseUrl,
                                    gpuModel = selectedNode.gpuModel
                                )
                                externalComputeAdapter.triggerAsyncRedrawAndHotSwap(
                                    bundle = bundle,
                                    promptPreset = "eastern_ink_ripple",
                                    renderer = sceneRenderer,
                                    onStatusChanged = { msg -> _phaseBStatusText.value = msg },
                                    onBundleUpdated = { updated ->
                                        _activeBundleFlow.value = updated
                                        refreshGalleryInventoryAsync()
                                        notifyWallpaperServiceBundleUpdated()
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        val rootContainer = FrameLayout(this).apply {
            addView(
                glSurfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                composeOverlayView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(rootContainer)

        requestAudioPermissionIfNeeded()
        detectFlymeWallpaperLockState()
        loadInitialWallpaperBundle()
        discoverPhaseBNodesAsync()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE)) {
            val w = glSurfaceView.width.coerceAtLeast(1)
            val h = glSurfaceView.height.coerceAtLeast(1)
            sceneRenderer.notifyTouchRipple(ev.x / w.toFloat(), ev.y / h.toFloat())
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        enableEdgeToEdgeImmersiveFullscreen()
        sceneRenderer.setRenderPaused(false)
        sceneRenderer.notifyStrWakeUpSelfCheck()
        glSurfaceView.onResume()
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        safetyMonitor.startMonitoring()
        transferServer.startServer()
        refreshGalleryInventoryAsync()
    }

    override fun onPause() {
        sceneRenderer.setRenderPaused(true)
        safetyMonitor.stopMonitoring()
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glSurfaceView.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        safetyMonitor.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        transferServer.stopServer()
        safetyMonitor.stopMonitoring()
        depthEngine.shutdown()
        glSurfaceView.queueEvent {
            sceneRenderer.release()
        }
        super.onDestroy()
    }

    fun requestSystemLiveWallpaperOrFallback(): Boolean {
        val wpManager = WallpaperManager.getInstance(this)
        if (!wpManager.isWallpaperSupported || !wpManager.isSetWallpaperAllowed) {
            _isWallpaperBindingLocked.value = true
            return false
        }
        return runCatching {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@ImmersiveShowcaseActivity, StarshineWallpaperService::class.java)
                )
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                true
            } else {
                _isWallpaperBindingLocked.value = true
                false
            }
        }.getOrElse {
            _isWallpaperBindingLocked.value = true
            false
        }
    }

    fun updateActiveStyleAndPersist(newStyle: WallpaperStyleConfig) {
        sceneRenderer.updateStyleConfig(newStyle)
        val current = _activeBundleFlow.value ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            quotaManager.updateStyleConfigForAsset(current.manifest.assetId, newStyle)
            refreshGalleryInventoryAsync()
            notifyWallpaperServiceBundleUpdated()
        }
    }

    private fun requestAudioPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun notifyWallpaperServiceBundleUpdated() {
        sendBroadcast(
            Intent(StarshineWallpaperService.ACTION_WALLPAPER_BUNDLE_UPDATED).setPackage(packageName)
        )
    }

    private fun refreshGalleryInventoryAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            _savedManifestsFlow.value = quotaManager.listAllManifests()
            val (usedBytes, _) = quotaManager.getQuotaSummary()
            _quotaUsedBytesFlow.value = usedBytes
        }
    }

    private fun discoverPhaseBNodesAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val nodes = externalComputeAdapter.discoverNodes(timeoutMs = 2000L)
            _discoveredPhaseBNodes.value = nodes
            _phaseBStatusText.value = if (nodes.isNotEmpty()) {
                "已发现 ${nodes.size} 个局域网算力节点，点击即可触发 H.265 2560×1600 循环重绘"
            } else {
                "当前处于纯车机离线闭环模式（未探测到局域网 ComfyUI 节点）"
            }
        }
    }

    private fun detectFlymeWallpaperLockState() {
        val wpManager = WallpaperManager.getInstance(this)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@ImmersiveShowcaseActivity, StarshineWallpaperService::class.java)
            )
        }
        val canResolve = intent.resolveActivity(packageManager) != null
        _isWallpaperBindingLocked.value = !wpManager.isWallpaperSupported || !wpManager.isSetWallpaperAllowed || !canResolve
    }

    private fun loadInitialWallpaperBundle() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bundle = quotaManager.loadActiveOrDefaultWallpaperBundle()
            if (bundle != null) {
                _activeBundleFlow.value = bundle
                sceneRenderer.bindWallpaperBundle(bundle)
            }
            refreshGalleryInventoryAsync()
        }
    }

    private fun enableEdgeToEdgeImmersiveFullscreen() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
