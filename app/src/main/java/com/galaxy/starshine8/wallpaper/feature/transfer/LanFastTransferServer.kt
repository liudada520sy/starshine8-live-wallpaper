package com.galaxy.starshine8.wallpaper.feature.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.galaxy.starshine8.wallpaper.core.contract.LoadedWallpaperBundle
import com.galaxy.starshine8.wallpaper.core.pipeline.SafeImageNormalizer
import com.galaxy.starshine8.wallpaper.core.pipeline.SerialDepthInferenceEngine
import com.galaxy.starshine8.wallpaper.core.storage.WallpaperQuotaManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID

typealias LocalWebServerManager = LanFastTransferServer

/**
 * 局域网免U盘扫码快传 HTTP 服务与交互式 H5 16:10 裁剪端（LanFastTransferServer / LocalWebServerManager）。
 */
class LanFastTransferServer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val imageNormalizer: SafeImageNormalizer,
    private val depthInferenceEngine: SerialDepthInferenceEngine,
    private val quotaManager: WallpaperQuotaManager,
    private val onNewWallpaperReady: (LoadedWallpaperBundle) -> Unit
) {

    companion object {
        const val LISTEN_PORT = 8980
    }

    enum class TransferStage(val progressPercent: Int, val label: String) {
        IDLE(0, "等待手机扫码连接"),
        RECEIVING(25, "正在接收手机高清画卷..."),
        NORMALIZING_16_10(55, "车机端 2560×1600 16:10 规整与主色调提取..."),
        INFERRING_DEPTH(82, "龍鹰一号 INT8 端侧深度场推理中 (<0.8s)..."),
        COMPLETED(100, "画卷已上屏生效")
    }

    data class TransferServerState(
        val candidateIps: List<String> = emptyList(),
        val primaryUploadUrl: String = "",
        val sessionToken: String = "",
        val qrCodeBitmap: Bitmap? = null,
        val stage: TransferStage = TransferStage.IDLE,
        val latestAssetId: String? = null,
        val lastErrorMessage: String? = null
    )

    private val _serverState = MutableStateFlow(TransferServerState())
    val serverState: StateFlow<TransferServerState> = _serverState.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptLoopJob: Job? = null
    private var sessionToken: String = generateShortToken()
    private var networkCallbackRegistered = false

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetworkEndpointsAndQrCode()
        }

        override fun onLost(network: Network) {
            refreshNetworkEndpointsAndQrCode()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            refreshNetworkEndpointsAndQrCode()
        }
    }

    fun startServer() {
        if (acceptLoopJob?.isActive == true) {
            refreshNetworkEndpointsAndQrCode()
            return
        }
        sessionToken = generateShortToken()
        registerNetworkCallbackSafely()
        refreshNetworkEndpointsAndQrCode()

        acceptLoopJob = coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", LISTEN_PORT))
                serverSocket = socket
                while (isActive && !socket.isClosed) {
                    val client = socket.accept()
                    launch(Dispatchers.IO) {
                        handleClientSocket(client)
                    }
                }
            }.onFailure { err ->
                _serverState.value = _serverState.value.copy(
                    lastErrorMessage = "局域网服务端口 $LISTEN_PORT 绑定异常: ${err.message}"
                )
            }
        }
    }

    fun stopServer() {
        unregisterNetworkCallbackSafely()
        acceptLoopJob?.cancel()
        acceptLoopJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun refreshNetworkEndpointsAndQrCode() {
        coroutineScope.launch(Dispatchers.Default) {
            val ips = enumerateLocalIpv4Addresses()
            val primaryIp = ips.firstOrNull() ?: "192.168.43.1"
            val candidatesParam = ips.joinToString(",")
            val url = "http://$primaryIp:$LISTEN_PORT/upload?token=$sessionToken&hosts=$candidatesParam"
            val qrBmp = generateQrCodeBitmap(url, 520)
            _serverState.value = _serverState.value.copy(
                candidateIps = ips,
                primaryUploadUrl = url,
                sessionToken = sessionToken,
                qrCodeBitmap = qrBmp,
                lastErrorMessage = null
            )
        }
    }

    private fun handleClientSocket(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = BufferedOutputStream(sock.getOutputStream())

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            val pathOnly = fullPath.substringBefore('?')
            val queryParams = parseQueryParams(fullPath.substringAfter('?', ""))

            when {
                method == "GET" && (pathOnly == "/" || pathOnly == "/upload") -> {
                    val htmlBytes = buildMobileCropHtmlPage(sessionToken).toByteArray(Charsets.UTF_8)
                    writeHttpResponse(output, "200 OK", "text/html; charset=utf-8", htmlBytes)
                }
                method == "GET" && pathOnly == "/api/v1/ping" -> {
                    val state = _serverState.value
                    val json = buildString {
                        append("""{"status":"ok","stage":"${state.stage.name}",""")
                        append(""""progress":${state.stage.progressPercent},""")
                        append(""""label":"${state.stage.label}",""")
                        append(""""latestAssetId":"${state.latestAssetId ?: ""}"}""")
                    }
                    writeHttpResponse(output, "200 OK", "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
                method == "POST" && pathOnly == "/api/v1/wallpaper/upload" -> {
                    val reqToken = headers["x-starshine-token"] ?: queryParams["token"] ?: ""
                    if (reqToken != sessionToken) {
                        val err = """{"error":"会话 Token 已过期，请重新扫描车机二维码"}""".toByteArray(Charsets.UTF_8)
                        writeHttpResponse(output, "403 Forbidden", "application/json; charset=utf-8", err)
                        return
                    }
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                    if (contentLength <= 0L || contentLength > 60L * 1024L * 1024L) {
                        val err = """{"error":"图片体积超出 60MB 安全上限"}""".toByteArray(Charsets.UTF_8)
                        writeHttpResponse(output, "413 Payload Too Large", "application/json; charset=utf-8", err)
                        return
                    }

                    val title = queryParams["title"]?.takeIf { it.isNotBlank() }?.take(36) ?: "星耀画卷·手机快传"
                    val tempUploadFile = File(context.cacheDir, "upload_${UUID.randomUUID()}.tmp")
                    try {
                        updateStage(TransferStage.RECEIVING)
                        streamRequestBodyToFile(input, contentLength, tempUploadFile)

                        updateStage(TransferStage.NORMALIZING_16_10)
                        val normalizedResult = imageNormalizer.normalizeTo16By10AndExtractPalette(tempUploadFile)

                        updateStage(TransferStage.INFERRING_DEPTH)
                        val depthBitmap = depthInferenceEngine.inferDepthMap640x400(normalizedResult.bitmap2560x1600)

                        val savedBundle = quotaManager.commitNewWallpaperBundle(
                            title = title,
                            colorBitmap2560x1600 = normalizedResult.bitmap2560x1600,
                            thumbBitmap640x400 = normalizedResult.thumbnail640x400,
                            depthBitmap640x400 = depthBitmap,
                            paletteColors = normalizedResult.paletteColors
                        )

                        updateStage(TransferStage.COMPLETED, savedBundle.manifest.assetId)
                        onNewWallpaperReady(savedBundle)

                        val respJson = """
                            {
                              "status": "ready",
                              "assetId": "${savedBundle.manifest.assetId}",
                              "title": "${savedBundle.manifest.title}",
                              "resolution": {"width": 2560, "height": 1600, "aspectRatio": "16:10"},
                              "dominantHex": "${savedBundle.manifest.palette.dominantHex}",
                              "vibrantHex": "${savedBundle.manifest.palette.vibrantHex}"
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8)
                        writeHttpResponse(output, "200 OK", "application/json; charset=utf-8", respJson)
                    } catch (e: Throwable) {
                        _serverState.value = _serverState.value.copy(
                            stage = TransferStage.IDLE,
                            lastErrorMessage = "图像解码或深度推理异常: ${e.message}"
                        )
                        val errJson = """{"error":"车机端处理失败: ${e.message?.replace("\"", "'")}"}"""
                            .toByteArray(Charsets.UTF_8)
                        writeHttpResponse(output, "500 Internal Server Error", "application/json; charset=utf-8", errJson)
                    } finally {
                        tempUploadFile.delete()
                    }
                }
                else -> {
                    val notFound = """{"error":"Not Found"}""".toByteArray(Charsets.UTF_8)
                    writeHttpResponse(output, "404 Not Found", "application/json; charset=utf-8", notFound)
                }
            }
        }
    }

    private fun streamRequestBodyToFile(input: InputStream, contentLength: Long, targetFile: File) {
        FileOutputStream(targetFile).use { fos ->
            val buf = ByteArray(16 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                fos.write(buf, 0, read)
                remaining -= read
            }
            fos.flush()
        }
    }

    private fun updateStage(stage: TransferStage, assetId: String? = _serverState.value.latestAssetId) {
        _serverState.value = _serverState.value.copy(
            stage = stage,
            latestAssetId = assetId,
            lastErrorMessage = null
        )
    }

    private fun writeHttpResponse(
        output: BufferedOutputStream,
        status: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var prevChar = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() > 0) baos.toString("UTF-8").trimEnd('\r') else null
            }
            if (b == '\n'.code) {
                break
            }
            if (prevChar != -1) {
                baos.write(prevChar)
            }
            prevChar = b
        }
        if (prevChar != -1 && prevChar != '\r'.code) {
            baos.write(prevChar)
        }
        return baos.toString("UTF-8")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                k to v
            } else null
        }.toMap()
    }

    private fun enumerateLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            result.add(ip)
                        }
                    }
                }
            }
        }
        return result.distinct()
    }

    private fun generateQrCodeBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        val darkObsidian = Color.parseColor("#0B0E14")
        val warmGoldWhite = Color.parseColor("#F5EFE6")
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) darkObsidian else warmGoldWhite
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private fun generateShortToken(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun registerNetworkCallbackSafely() {
        if (networkCallbackRegistered) return
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallbackSafely() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    private fun buildMobileCropHtmlPage(token: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<title>银河星耀8 · 16:10 座舱画卷快传</title>
<style>
  :root {
    --obsidian: rgba(11, 14, 20, 0.88);
    --gold: #D49B54;
    --text: #F2DFC4;
    --muted: rgba(242, 223, 196, 0.68);
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: radial-gradient(circle at 50% 0%, #1B2A38 0%, #07090D 100%);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 18px 14px;
  }
  .glass-card {
    width: 100%;
    max-width: 480px;
    background: var(--obsidian);
    backdrop-filter: blur(24px);
    -webkit-backdrop-filter: blur(24px);
    border: 1px solid rgba(255, 255, 255, 0.14);
    border-radius: 22px;
    padding: 20px;
    box-shadow: 0 18px 42px rgba(0, 0, 0, 0.58);
  }
  h1 { font-size: 20px; font-weight: 600; letter-spacing: 0.5px; margin-bottom: 4px; }
  p.sub { font-size: 13px; color: var(--muted); margin-bottom: 14px; }
  .viewport-wrap {
    width: 100%;
    aspect-ratio: 16 / 10;
    background: #05070A;
    border: 1.5px solid rgba(212, 155, 84, 0.60);
    border-radius: 14px;
    overflow: hidden;
    position: relative;
    touch-action: none;
    margin-bottom: 12px;
  }
  canvas#previewCanvas { width: 100%; height: 100%; display: block; }
  .grid-overlay {
    position: absolute; inset: 0; pointer-events: none;
    background-image:
      linear-gradient(to right, rgba(255,255,255,0.10) 1px, transparent 1px),
      linear-gradient(to bottom, rgba(255,255,255,0.10) 1px, transparent 1px);
    background-size: 33.333% 33.333%;
  }
  .horizon-line {
    position: absolute; left: 0; right: 0; top: 72%;
    border-top: 1px dashed rgba(212, 155, 84, 0.55);
    pointer-events: none;
  }
  .horizon-tag {
    position: absolute; right: 8px; top: calc(72% - 18px);
    font-size: 10px; color: var(--gold);
    background: rgba(11, 14, 20, 0.72);
    padding: 2px 6px; border-radius: 4px;
    pointer-events: none;
  }
  .preset-row {
    display: flex; gap: 8px; margin-bottom: 12px;
  }
  .chip {
    flex: 1; padding: 8px 6px; font-size: 12px; text-align: center;
    border-radius: 10px; background: rgba(255,255,255,0.06);
    border: 1px solid rgba(255,255,255,0.14); color: var(--text);
    cursor: pointer; user-select: none;
  }
  .chip.active {
    background: rgba(212, 155, 84, 0.24);
    border-color: var(--gold); color: var(--gold); font-weight: 600;
  }
  .controls { display: flex; flex-direction: column; gap: 11px; }
  .row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
  label.slider-label { font-size: 13px; color: var(--muted); min-width: 68px; }
  input[type="range"] { flex: 1; accent-color: var(--gold); }
  input[type="text"].title-input {
    flex: 1; background: rgba(255,255,255,0.06);
    border: 1px solid rgba(255,255,255,0.16);
    border-radius: 10px; padding: 9px 12px; color: var(--text); font-size: 14px;
  }
  .btn {
    width: 100%; min-height: 48px; padding: 13px;
    border-radius: 12px; border: 1px solid rgba(212, 155, 84, 0.45);
    background: linear-gradient(135deg, #D49B54 0%, #9E6B2B 100%);
    color: #0B0E14; font-size: 15px; font-weight: 600;
    text-align: center; cursor: pointer; transition: opacity 0.2s cubic-bezier(0.22, 1, 0.36, 1);
  }
  .btn:disabled { opacity: 0.45; cursor: not-allowed; }
  .btn-secondary {
    background: rgba(255, 255, 255, 0.07); color: var(--text);
    border-color: rgba(255, 255, 255, 0.18);
  }
  .progress-wrap {
    width: 100%; height: 8px; background: rgba(255,255,255,0.08);
    border-radius: 4px; overflow: hidden; display: none; margin-top: 6px;
  }
  .progress-bar {
    width: 0%; height: 100%; background: var(--gold);
    transition: width 0.2s cubic-bezier(0.22, 1, 0.36, 1);
  }
  #statusBox {
    margin-top: 12px; font-size: 13px; color: var(--gold);
    text-align: center; min-height: 20px; line-height: 1.45;
  }
</style>
</head>
<body>
  <div class="glass-card">
    <h1>银河星耀8 · 15.4" 巨幕壁纸快传</h1>
    <p class="sub">16:10 (2560×1600) 交互式取景 · 单指拖拽 / 双指缩放 · 零损直传车机</p>
    <div class="viewport-wrap" id="touchArea">
      <canvas id="previewCanvas" width="2560" height="1600"></canvas>
      <div class="grid-overlay"></div>
      <div class="horizon-line"></div>
      <div class="horizon-tag">0.72 水波倒影分界线</div>
    </div>
    <div class="preset-row">
      <div class="chip active" id="presetCenter" onclick="applyFocalPreset('center')">居中构图</div>
      <div class="chip" id="presetPortrait" onclick="applyFocalPreset('portrait42')">人像黄金点 (42%)</div>
      <div class="chip" id="presetHorizon" onclick="applyFocalPreset('horizon62')">远山三分线 (62%)</div>
    </div>
    <div class="controls">
      <div class="row">
        <label class="slider-label">构图缩放</label>
        <input type="range" id="zoomSlider" min="1.0" max="3.0" step="0.02" value="1.0" />
        <span id="zoomValueText" style="font-size:12px;min-width:38px;text-align:right;">1.00x</span>
      </div>
      <div class="row">
        <label class="slider-label">画卷题名</label>
        <input type="text" id="titleInput" class="title-input" maxlength="28" value="西湖晨雾·星耀画卷" />
      </div>
      <input type="file" id="fileInput" accept="image/jpeg,image/png,image/webp" style="display:none" />
      <button class="btn btn-secondary" id="selectBtn" onclick="document.getElementById('fileInput').click()">从手机相册选择照片</button>
      <button class="btn" id="uploadBtn" onclick="exportAndUpload()">立即生成 2560×1600 3D 壁纸</button>
      <div class="progress-wrap" id="progressWrap">
        <div class="progress-bar" id="progressBar"></div>
      </div>
    </div>
    <div id="statusBox">请选择一张照片，单指拖拽或双指捏合可微调 16:10 焦点区域</div>
  </div>
<script>
  const TOKEN = "$token";
  const canvas = document.getElementById('previewCanvas');
  const ctx = canvas.getContext('2d');
  const zoomSlider = document.getElementById('zoomSlider');
  const zoomValueText = document.getElementById('zoomValueText');
  const titleInput = document.getElementById('titleInput');
  const uploadBtn = document.getElementById('uploadBtn');
  const progressWrap = document.getElementById('progressWrap');
  const progressBar = document.getElementById('progressBar');
  const statusBox = document.getElementById('statusBox');

  let img = new Image();
  let imgLoaded = false;
  let isUploading = false;
  let scale = 1.0, offsetX = 0, offsetY = 0;
  let startX = 0, startY = 0, dragging = false;
  let initialPinchDist = 0, initialPinchScale = 1.0;

  document.getElementById('fileInput').addEventListener('change', (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const baseName = file.name.replace(/\.[^/.]+$/, "").slice(0, 20);
    if (baseName) titleInput.value = baseName + "·星耀画卷";
    const reader = new FileReader();
    reader.onload = (ev) => {
      img = new Image();
      img.onload = () => {
        imgLoaded = true;
        scale = 1.0;
        zoomSlider.value = "1.0";
        zoomValueText.innerText = "1.00x";
        applyFocalPreset('portrait42');
        statusBox.innerText = "已载入照片 (" + img.width + "×" + img.height + ")，已自动对齐黄金焦点";
      };
      img.src = ev.target.result;
    };
    reader.readAsDataURL(file);
  });

  zoomSlider.addEventListener('input', () => {
    scale = parseFloat(zoomSlider.value);
    zoomValueText.innerText = scale.toFixed(2) + "x";
    clampOffsets();
    renderCropCanvas();
  });

  const touchArea = document.getElementById('touchArea');
  touchArea.addEventListener('pointerdown', (e) => {
    if (!imgLoaded || isUploading) return;
    dragging = true;
    startX = e.clientX - offsetX;
    startY = e.clientY - offsetY;
    touchArea.setPointerCapture(e.pointerId);
  });
  touchArea.addEventListener('pointermove', (e) => {
    if (!dragging || !imgLoaded || isUploading) return;
    offsetX = e.clientX - startX;
    offsetY = e.clientY - startY;
    clampOffsets();
    renderCropCanvas();
  });
  touchArea.addEventListener('pointerup', () => { dragging = false; });
  touchArea.addEventListener('pointercancel', () => { dragging = false; });

  touchArea.addEventListener('touchstart', (e) => {
    if (e.touches.length === 2 && imgLoaded && !isUploading) {
      dragging = false;
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dy = e.touches[0].clientY - e.touches[1].clientY;
      initialPinchDist = Math.hypot(dx, dy);
      initialPinchScale = scale;
    }
  }, { passive: true });

  touchArea.addEventListener('touchmove', (e) => {
    if (e.touches.length === 2 && imgLoaded && !isUploading && initialPinchDist > 10) {
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dy = e.touches[0].clientY - e.touches[1].clientY;
      const ratio = Math.hypot(dx, dy) / initialPinchDist;
      scale = Math.min(3.0, Math.max(1.0, initialPinchScale * ratio));
      zoomSlider.value = scale.toFixed(2);
      zoomValueText.innerText = scale.toFixed(2) + "x";
      clampOffsets();
      renderCropCanvas();
    }
  }, { passive: true });

  function clampOffsets() {
    if (!imgLoaded) return;
    const baseScale = Math.max(2560 / img.width, 1600 / img.height);
    const finalScale = baseScale * scale;
    const maxShiftX = Math.max(0, (img.width * finalScale - 2560) / 8.0);
    const maxShiftY = Math.max(0, (img.height * finalScale - 1600) / 8.0);
    offsetX = Math.min(maxShiftX, Math.max(-maxShiftX, offsetX));
    offsetY = Math.min(maxShiftY, Math.max(-maxShiftY, offsetY));
  }

  function applyFocalPreset(preset) {
    document.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
    if (preset === 'center') document.getElementById('presetCenter').classList.add('active');
    if (preset === 'portrait42') document.getElementById('presetPortrait').classList.add('active');
    if (preset === 'horizon62') document.getElementById('presetHorizon').classList.add('active');
    if (!imgLoaded) return;

    const baseScale = Math.max(2560 / img.width, 1600 / img.height);
    const finalScale = baseScale * scale;
    const overflowY = Math.max(0, img.height * finalScale - 1600);
    offsetX = 0;
    if (preset === 'center') {
      offsetY = 0;
    } else if (preset === 'portrait42') {
      offsetY = (overflowY * (0.5 - 0.42)) / 4.0;
    } else if (preset === 'horizon62') {
      offsetY = (overflowY * (0.5 - 0.62)) / 4.0;
    }
    clampOffsets();
    renderCropCanvas();
  }

  function renderCropCanvas() {
    ctx.fillStyle = '#07090D';
    ctx.fillRect(0, 0, 2560, 1600);
    if (!imgLoaded) return;
    const baseScale = Math.max(2560 / img.width, 1600 / img.height);
    const finalScale = baseScale * scale;
    const drawW = img.width * finalScale;
    const drawH = img.height * finalScale;
    const dx = (2560 - drawW) / 2 + offsetX * 4.0;
    const dy = (1600 - drawH) / 2 + offsetY * 4.0;
    ctx.drawImage(img, dx, dy, drawW, drawH);
  }

  function encodeCanvasBlob(callback) {
    canvas.toBlob((webpBlob) => {
      if (webpBlob && webpBlob.size > 0) {
        callback(webpBlob, 'image/webp');
      } else {
        canvas.toBlob((jpegBlob) => callback(jpegBlob, 'image/jpeg'), 'image/jpeg', 0.94);
      }
    }, 'image/webp', 0.94);
  }

  function exportAndUpload() {
    if (!imgLoaded) {
      statusBox.innerText = "请先从手机相册选择一张图片";
      return;
    }
    if (isUploading) return;
    isUploading = true;
    uploadBtn.disabled = true;
    progressWrap.style.display = 'block';
    progressBar.style.width = '8%';
    statusBox.innerText = "正在导出 2560×1600 (16:10) 高清画卷...";

    encodeCanvasBlob((blob, mimeType) => {
      if (!blob) {
        isUploading = false;
        uploadBtn.disabled = false;
        statusBox.innerText = "图像编码失败，请重试";
        return;
      }
      const title = encodeURIComponent(titleInput.value.trim() || "星耀画卷·手机快传");
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/v1/wallpaper/upload?token=' + encodeURIComponent(TOKEN) + '&title=' + title, true);
      xhr.setRequestHeader('X-Starshine-Token', TOKEN);
      xhr.setRequestHeader('Content-Type', mimeType);

      xhr.upload.onprogress = (ev) => {
        if (ev.lengthComputable) {
          const pct = Math.round((ev.loaded / ev.total) * 65);
          progressBar.style.width = Math.max(10, pct) + '%';
          statusBox.innerText = "正在高速直传车机 (" + Math.round((ev.loaded / 1024)) + " KB)...";
        }
      };

      const pollTimer = setInterval(async () => {
        try {
          const pingRes = await fetch('/api/v1/ping');
          if (pingRes.ok) {
            const p = await pingRes.json();
            if (p.progress > 25) {
              progressBar.style.width = p.progress + '%';
              statusBox.innerText = p.label + " (" + p.progress + "%)";
            }
          }
        } catch (_) {}
      }, 300);

      xhr.onload = () => {
        clearInterval(pollTimer);
        isUploading = false;
        uploadBtn.disabled = false;
        try {
          const data = JSON.parse(xhr.responseText || '{}');
          if (xhr.status >= 200 && xhr.status < 300) {
            progressBar.style.width = '100%';
            statusBox.innerText = "上屏成功！车机已激活 3D 景深与中式水波 (" + data.assetId + ")";
          } else {
            statusBox.innerText = "上传失败: " + (data.error || xhr.statusText);
          }
        } catch (err) {
          statusBox.innerText = "解析车机响应失败，请重试";
        }
      };

      xhr.onerror = () => {
        clearInterval(pollTimer);
        isUploading = false;
        uploadBtn.disabled = false;
        statusBox.innerText = "网络中断，请确认手机与星耀8车机处于同一热点/Wi-Fi";
      };

      xhr.send(blob);
    });
  }
</script>
</body>
</html>
    """.trimIndent()
}
