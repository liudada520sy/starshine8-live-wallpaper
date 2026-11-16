package com.galaxy.starshine8.wallpaper.feature.phaseb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.galaxy.starshine8.wallpaper.core.contract.BaseVisualMode
import com.galaxy.starshine8.wallpaper.core.contract.ComputeNodeEndpoint
import com.galaxy.starshine8.wallpaper.core.contract.DynamicSceneRenderer
import com.galaxy.starshine8.wallpaper.core.contract.ExternalComputeAdapter
import com.galaxy.starshine8.wallpaper.core.contract.LoadedWallpaperBundle
import com.galaxy.starshine8.wallpaper.core.contract.RemoteJobHandle
import com.galaxy.starshine8.wallpaper.core.storage.WallpaperQuotaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 车机端 Phase B 外部算力适配器实现（LanExternalComputeAdapter）。
 * 严格实现 ADR-001 第 5 节锁定的 ExternalComputeAdapter 接口：
 * 1. 使用 Android NsdManager 自动发现局域网内 _galaxy-wallpaper._tcp 服务节点（如家庭 RTX 4060 Ti 主机），同时支持手动/扫码绑定节点 URL。
 * 2. 非阻塞体验铁律：调用 submitVideoRedrawJob() 后车机前台继续以 Phase A 实时着色器（3D 视差+中式涟漪）渲染；
 *    后台协程以指数退避策略轮询进度，超时（> 15s 无响应或驶离家庭 Wi-Fi 断网）时自动静默取消，绝不弹窗干扰驾驶。
 * 3. 视频生成完毕后支持 Range 断点续传流式下载至临时文件，经 SHA-256 完整性校验通过后原子更新 manifest.json，
 *    并触发 DynamicSceneRenderer 无缝热切换至车机 VPU（MediaCodec/MediaPlayer）零 GPU 负担硬解循环播放。
 */
class LanExternalComputeAdapter(
    private val context: Context,
    private val quotaManager: WallpaperQuotaManager,
    private val coroutineScope: CoroutineScope
) : ExternalComputeAdapter {

    companion object {
        const val SERVICE_TYPE = "_galaxy-wallpaper._tcp."
        private const val POLL_TOTAL_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    @Serializable
    private data class JobSubmitResponse(
        val jobId: String,
        val status: String,
        val assetId: String
    )

    @Serializable
    private data class JobStatusResponse(
        val jobId: String,
        val status: String, // "queued" | "processing" | "completed" | "failed"
        val progressPercent: Int = 0,
        val sha256: String? = null,
        val videoUrl: String? = null,
        val errorMessage: String? = null
    )

    private val jsonCodec = Json { ignoreUnknownKeys = true }
    private val manualEndpoints = ConcurrentHashMap<String, ComputeNodeEndpoint>()
    private val latestJobSha256Map = ConcurrentHashMap<String, String>()

    fun registerManualEndpoint(nodeName: String, baseUrl: String, gpuModel: String = "Remote RTX 4060 Ti Node") {
        val normalizedUrl = baseUrl.trimEnd('/')
        manualEndpoints[normalizedUrl] = ComputeNodeEndpoint(
            nodeName = nodeName,
            baseUrl = normalizedUrl,
            gpuModel = gpuModel,
            isMdnsDiscovered = false
        )
    }

    override suspend fun discoverNodes(timeoutMs: Long): List<ComputeNodeEndpoint> = withContext(Dispatchers.IO) {
        val discovered = ConcurrentHashMap<String, ComputeNodeEndpoint>()
        discovered.putAll(manualEndpoints)

        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return@withContext discovered.values.toList()

        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        runCatching { nsdManager.stopServiceDiscovery(this) }
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        runCatching { nsdManager.stopServiceDiscovery(this) }
                    }

                    override fun onDiscoveryStarted(serviceType: String?) = Unit

                    override fun onDiscoveryStopped(serviceType: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                        if (serviceInfo == null) return
                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) = Unit

                                override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                                    val hostAddress = resolvedInfo?.host?.hostAddress ?: return
                                    val port = resolvedInfo.port
                                    val baseUrl = "http://$hostAddress:$port"
                                    val nodeName = resolvedInfo.serviceName ?: "Starshine-AIGC-Node"
                                    discovered[baseUrl] = ComputeNodeEndpoint(
                                        nodeName = nodeName,
                                        baseUrl = baseUrl,
                                        gpuModel = "LAN RTX 4060 Ti ComfyUI",
                                        isMdnsDiscovered = true
                                    )
                                }
                            }
                        )
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
                }

                runCatching {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                }.onFailure {
                    if (cont.isActive) cont.resume(Unit)
                }

                cont.invokeOnCancellation {
                    runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                }
            }
        }

        discovered.values.toList()
    }

    override suspend fun submitVideoRedrawJob(
        assetId: String,
        normalizedWebpFile: File,
        promptPreset: String
    ): RemoteJobHandle = withContext(Dispatchers.IO) {
        val nodes = discoverNodes(timeoutMs = 1500L)
        val targetNode = nodes.firstOrNull()
            ?: ComputeNodeEndpoint(
                nodeName = "Default-LAN-Workstation",
                baseUrl = "http://192.168.43.100:8090",
                gpuModel = "RTX 4060 Ti",
                isMdnsDiscovered = false
            )

        val boundary = "----StarshineBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val submitUrl = URL("${targetNode.baseUrl}/api/v1/aigc/jobs")
        val conn = (submitUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        DataOutputStream(conn.outputStream).use { dos ->
            // 1. asset_id 字段
            writeFormField(dos, boundary, "asset_id", assetId)
            // 2. prompt_preset 字段
            writeFormField(dos, boundary, "prompt_preset", promptPreset)
            // 3. file 二进制流
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${normalizedWebpFile.name}\"\r\n")
            dos.writeBytes("Content-Type: image/webp\r\n\r\n")
            FileInputStream(normalizedWebpFile).use { fis ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = fis.read(buf)
                    if (read <= 0) break
                    dos.write(buf, 0, read)
                }
            }
            dos.writeBytes("\r\n--$boundary--\r\n")
            dos.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            throw IllegalStateException("Phase B job submission failed with HTTP $responseCode")
        }
        val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = jsonCodec.decodeFromString<JobSubmitResponse>(responseBody)

        RemoteJobHandle(
            jobId = parsed.jobId,
            assetId = assetId,
            endpointUrl = targetNode.baseUrl,
            submittedAtEpochMs = System.currentTimeMillis()
        )
    }

    override suspend fun pollAndDownloadMp4(
        handle: RemoteJobHandle,
        destinationMp4File: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val startMs = System.currentTimeMillis()
            var backoffDelayMs = 800L
            var completedSha256: String? = null

            // 指数退避轮询：总耗时超过 15s 或断网时立即静默退出，绝不阻塞车机
            while (System.currentTimeMillis() - startMs < POLL_TOTAL_TIMEOUT_MS) {
                val statusUrl = URL("${handle.endpointUrl}/api/v1/aigc/jobs/${handle.jobId}")
                val statusConn = (statusUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = CONNECT_TIMEOUT_MS
                }
                if (statusConn.responseCode !in 200..299) {
                    throw IllegalStateException("Status poll HTTP ${statusConn.responseCode}")
                }
                val body = statusConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val statusResp = jsonCodec.decodeFromString<JobStatusResponse>(body)

                when (statusResp.status) {
                    "completed" -> {
                        completedSha256 = statusResp.sha256
                        break
                    }
                    "failed" -> {
                        throw IllegalStateException("Remote ComfyUI job failed: ${statusResp.errorMessage}")
                    }
                    else -> {
                        delay(backoffDelayMs)
                        backoffDelayMs = (backoffDelayMs * 1.6).toLong().coerceAtMost(3500L)
                    }
                }
            }

            val expectedSha = completedSha256
                ?: throw IllegalStateException("Phase B polling timed out (>15s); silently keeping Phase A shader active.")

            // 支持 HTTP Range 断点续传的流式视频下载
            val existingBytes = if (destinationMp4File.exists()) destinationMp4File.length() else 0L
            val videoUrl = URL("${handle.endpointUrl}/api/v1/aigc/jobs/${handle.jobId}/video")
            val videoConn = (videoUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val code = videoConn.responseCode
            val appendMode = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("Video stream download failed with HTTP $code")
            }

            BufferedInputStream(videoConn.inputStream).use { bis ->
                FileOutputStream(destinationMp4File, appendMode).use { fos ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = bis.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                    }
                    fos.flush()
                }
            }

            // 校验下载后视频的 SHA-256 完整性
            val actualSha = quotaManager.computeFileSha256(destinationMp4File)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                destinationMp4File.delete()
                throw IllegalStateException("SHA-256 mismatch: expected=$expectedSha, actual=$actualSha")
            }
            latestJobSha256Map[handle.jobId] = actualSha
        }
    }

    /**
     * 非阻塞端到端协同调度入口：
     * 提交任务期间前台保持 Phase A 实时着色器运行；下载并校验 SHA-256 成功后自动热切换至车机 VPU 硬解播放。
     */
    fun triggerAsyncRedrawAndHotSwap(
        bundle: LoadedWallpaperBundle,
        promptPreset: String,
        renderer: DynamicSceneRenderer,
        onStatusChanged: (String) -> Unit,
        onBundleUpdated: (LoadedWallpaperBundle) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val assetId = bundle.manifest.assetId
            val colorWebpFile = File(
                File(quotaManager.wallpapersRootDir, assetId),
                WallpaperQuotaManager.FILE_COLOR_WEBP
            )
            if (!colorWebpFile.exists()) {
                onStatusChanged("本地主图不存在，保持路线1实时着色器运行")
                return@launch
            }

            onStatusChanged("已提交外部算力重绘，当前由路线1实时着色器即时渲染...")
            val handle = runCatching {
                submitVideoRedrawJob(assetId, colorWebpFile, promptPreset)
            }.getOrElse {
                onStatusChanged("未检测到可用外部算力节点，已静默保持路线1实时渲染")
                return@launch
            }

            val tempMp4 = File(context.cacheDir, "phaseb_${handle.jobId}.mp4.tmp")
            val downloadResult = pollAndDownloadMp4(handle, tempMp4)
            if (downloadResult.isSuccess) {
                val expectedSha = latestJobSha256Map[handle.jobId] ?: quotaManager.computeFileSha256(tempMp4)
                val updatedBundle = quotaManager.attachVerifiedPhaseBVideo(
                    assetId = assetId,
                    downloadedMp4TempFile = tempMp4,
                    expectedSha256 = expectedSha,
                    sourceNodeUrl = handle.endpointUrl
                )
                if (updatedBundle != null) {
                    val hotSwapConfig = updatedBundle.manifest.styleConfig.copy(
                        baseMode = BaseVisualMode.PHASE_B_HARDWARE_VIDEO
                    )
                    quotaManager.updateStyleConfigForAsset(assetId, hotSwapConfig)
                    val finalBundle = updatedBundle.copy(
                        manifest = updatedBundle.manifest.copy(styleConfig = hotSwapConfig)
                    )
                    renderer.bindWallpaperBundle(finalBundle)
                    renderer.updateStyleConfig(hotSwapConfig)
                    onBundleUpdated(finalBundle)
                    onStatusChanged("H.265 2560×1600 循环视频校验通过，已无缝切换至 VPU 硬解播放")
                } else {
                    onStatusChanged("视频 SHA-256 校验未通过，继续保持路线1实时渲染")
                }
            } else {
                tempMp4.delete()
                onStatusChanged("外部算力超时或网络离开 (>15s)，已静默维持路线1实时渲染")
            }
        }
    }

    private fun writeFormField(dos: DataOutputStream, boundary: String, name: String, value: String) {
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        dos.write(value.toByteArray(Charsets.UTF_8))
        dos.writeBytes("\r\n")
    }
}
