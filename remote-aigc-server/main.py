#!/usr/bin/env python3
"""
2025款吉利银河星耀8 — Phase B 外部算力协同服务端 (FastAPI + Zeroconf mDNS + ComfyUI / FFmpeg H.265 60fps)
P2 工程化升级特性：
1. FFmpeg 兜底管线升级至 2560×1600@60fps（d=360, 2*PI*on/360），匹配星耀8 15.4 寸 60fps 屏幕，首尾 0 跳帧无缝循环。
2. 支持根据 prompt_preset（eastern_ink_ripple / tyndall_dawn / sakura_breeze / jiangnan_rain / winter_snow）
   自动映射差异化调色与动态呼吸运镜参数。
3. 新增后台定时清理协程（cleanup_expired_jobs_loop），每小时自动清理超过 24 小时的临时任务目录与注册表项，防止磁盘溢出。
"""

import asyncio
import hashlib
import json
import os
import shutil
import socket
import subprocess
import time
import urllib.request
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional, Tuple

from fastapi import BackgroundTasks, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from zeroconf import IPVersion, ServiceInfo, Zeroconf

SERVICE_PORT = int(os.environ.get("STARSHINE_AIGC_PORT", "8090"))
COMFYUI_API_URL = os.environ.get("COMFYUI_API_URL", "http://127.0.0.1:8188")
WORKSPACE_DIR = Path(os.environ.get("STARSHINE_AIGC_WORKDIR", "./starshine_aigc_workspace")).resolve()
WORKSPACE_DIR.mkdir(parents=True, exist_ok=True)
JOB_RETENTION_SECONDS = 24 * 3600


@dataclass
class AigcJobRecord:
    job_id: str
    asset_id: str
    prompt_preset: str
    status: str  # "queued" | "processing" | "completed" | "failed"
    progress_percent: int
    input_image_path: Path
    output_video_path: Path
    sha256: Optional[str] = None
    error_message: Optional[str] = None
    created_at_ms: int = 0


JOBS_REGISTRY: Dict[str, AigcJobRecord] = {}

PRESET_GRADING_MAP: Dict[str, Tuple[str, float, float]] = {
    # preset: (ffmpeg eq/color filter, zoom_amp, pan_amp)
    "eastern_ink_ripple": ("eq=contrast=1.04:saturation=1.06", 0.015, 18.0),
    "tyndall_dawn": ("eq=contrast=1.06:brightness=0.02:saturation=1.10", 0.018, 22.0),
    "sakura_breeze": ("eq=contrast=1.03:saturation=1.12,colorbalance=rs=0.03:bs=0.02", 0.016, 20.0),
    "jiangnan_rain": ("eq=contrast=1.05:saturation=0.92,colorbalance=bs=0.04", 0.012, 14.0),
    "winter_snow": ("eq=contrast=1.07:brightness=0.01:saturation=0.95,colorbalance=bs=0.05", 0.014, 16.0),
}


def detect_lan_ipv4() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("10.255.255.255", 1))
        ip = sock.getsockname()[0]
    except OSError:
        ip = "127.0.0.1"
    finally:
        sock.close()
    return ip


class StarshineMdnsBroadcaster:
    def __init__(self, port: int) -> None:
        self.port = port
        self.zeroconf: Optional[Zeroconf] = None
        self.service_info: Optional[ServiceInfo] = None

    def register(self) -> None:
        lan_ip = detect_lan_ipv4()
        hostname = socket.gethostname()
        self.zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
        self.service_info = ServiceInfo(
            type_="_galaxy-wallpaper._tcp.local.",
            name=f"Starshine8-RTX4060Ti-{hostname}._galaxy-wallpaper._tcp.local.",
            addresses=[socket.inet_aton(lan_ip)],
            port=self.port,
            properties={
                "model": "NVIDIA-GeForce-RTX-4060-Ti",
                "resolution": "2560x1600",
                "fps": "60",
                "codec": "hevc_h265_crf20",
            },
            server=f"{hostname}.local.",
        )
        self.zeroconf.register_service(self.service_info)

    def unregister(self) -> None:
        if self.zeroconf and self.service_info:
            try:
                self.zeroconf.unregister_service(self.service_info)
            finally:
                self.zeroconf.close()


mdns_broadcaster = StarshineMdnsBroadcaster(port=SERVICE_PORT)


async def cleanup_expired_jobs_loop() -> None:
    """后台定时清理超过 24 小时的临时任务目录，防止 RTX 4060 Ti 宿主磁盘空间无限增长。"""
    while True:
        await asyncio.sleep(3600)
        now_ms = int(time.time() * 1000)
        cutoff_ms = now_ms - JOB_RETENTION_SECONDS * 1000
        expired_ids = [
            jid for jid, rec in list(JOBS_REGISTRY.items())
            if rec.created_at_ms > 0 and rec.created_at_ms < cutoff_ms
        ]
        for jid in expired_ids:
            JOBS_REGISTRY.pop(jid, None)
            job_dir = WORKSPACE_DIR / jid
            if job_dir.exists():
                shutil.rmtree(job_dir, ignore_errors=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        mdns_broadcaster.register()
    except Exception:
        pass
    cleanup_task = asyncio.create_task(cleanup_expired_jobs_loop())
    try:
        yield
    finally:
        cleanup_task.cancel()
        try:
            mdns_broadcaster.unregister()
        except Exception:
            pass


app = FastAPI(
    title="Geely Galaxy Starshine 8 Phase B AIGC Compute Node",
    version="1.3.0",
    lifespan=lifespan,
)


def compute_sha256(file_path: Path) -> str:
    digest = hashlib.sha256()
    with file_path.open("rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def try_trigger_comfyui_workflow(image_path: Path, prompt_preset: str) -> bool:
    """探测本地 ComfyUI (127.0.0.1:8188) 是否在线并提交 SVD/AnimateDiff 工作流。"""
    try:
        req = urllib.request.Request(f"{COMFYUI_API_URL}/system_stats", method="GET")
        with urllib.request.urlopen(req, timeout=1.2) as resp:
            if resp.status == 200:
                payload = json.dumps(
                    {
                        "client_id": "starshine8_cockpit",
                        "prompt": {
                            "preset": prompt_preset,
                            "source_image": str(image_path),
                            "target_width": 2560,
                            "target_height": 1600,
                            "fps": 60,
                        },
                    }
                ).encode("utf-8")
                post_req = urllib.request.Request(
                    f"{COMFYUI_API_URL}/prompt",
                    data=payload,
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(post_req, timeout=2.0):
                    return True
    except Exception:
        return False
    return False


def render_seamless_h265_loop_mp4(source_img: Path, output_mp4: Path, prompt_preset: str = "eastern_ink_ripple") -> None:
    """
    使用 FFmpeg 生成精确 2560×1600 (16:10)、60fps、H.265 (libx265 crf=20)、首尾无缝循环的 6 秒高美感微动视频。
    采用 360 帧正弦呼吸缩放表达式：2*PI*on/360，确保第 0 帧与第 360 帧完全重合实现零跳帧循环。
    """
    color_filter, zoom_amp, pan_amp = PRESET_GRADING_MAP.get(
        prompt_preset,
        PRESET_GRADING_MAP["eastern_ink_ripple"],
    )
    filter_complex = (
        f"scale=2560:1600:force_original_aspect_ratio=increase,"
        f"crop=2560:1600,"
        f"{color_filter},"
        f"zoompan=z='1.025+{zoom_amp}*sin(2*PI*on/360)':"
        f"x='iw/2-(iw/zoom/2)+{pan_amp}*sin(2*PI*on/360)':"
        f"y='ih/2-(ih/zoom/2)+{pan_amp * 0.55:.1f}*cos(2*PI*on/360)':"
        f"d=360:s=2560x1600:fps=60,"
        f"format=yuv420p"
    )
    cmd = [
        "ffmpeg",
        "-y",
        "-loop",
        "1",
        "-i",
        str(source_img),
        "-vf",
        filter_complex,
        "-t",
        "6",
        "-c:v",
        "libx265",
        "-crf",
        "20",
        "-preset",
        "fast",
        "-tag:v",
        "hvc1",
        "-movflags",
        "+faststart",
        str(output_mp4),
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


async def execute_video_redraw_pipeline(job_id: str) -> None:
    record = JOBS_REGISTRY.get(job_id)
    if not record:
        return
    try:
        record.status = "processing"
        record.progress_percent = 20

        _ = await asyncio.to_thread(
            try_trigger_comfyui_workflow,
            record.input_image_path,
            record.prompt_preset,
        )
        record.progress_percent = 55

        await asyncio.to_thread(
            render_seamless_h265_loop_mp4,
            record.input_image_path,
            record.output_video_path,
            record.prompt_preset,
        )
        record.progress_percent = 90

        record.sha256 = await asyncio.to_thread(compute_sha256, record.output_video_path)
        record.progress_percent = 100
        record.status = "completed"
    except Exception as exc:
        record.status = "failed"
        record.error_message = str(exc)


@app.post("/api/v1/aigc/jobs")
async def submit_aigc_job(
    background_tasks: BackgroundTasks,
    asset_id: str = Form(...),
    prompt_preset: str = Form("eastern_ink_ripple"),
    file: UploadFile = File(...),
):
    job_id = f"job_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    job_dir = WORKSPACE_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)

    input_path = job_dir / "source_2560x1600.webp"
    output_path = job_dir / "video_loop.mp4"

    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Uploaded image payload is empty")
    input_path.write_bytes(content)

    record = AigcJobRecord(
        job_id=job_id,
        asset_id=asset_id,
        prompt_preset=prompt_preset,
        status="queued",
        progress_percent=5,
        input_image_path=input_path,
        output_video_path=output_path,
        created_at_ms=int(time.time() * 1000),
    )
    JOBS_REGISTRY[job_id] = record
    background_tasks.add_task(execute_video_redraw_pipeline, job_id)

    return JSONResponse(
        status_code=200,
        content={
            "jobId": job_id,
            "status": record.status,
            "assetId": asset_id,
        },
    )


@app.get("/api/v1/aigc/jobs/{job_id}")
async def get_aigc_job_status(job_id: str, request: Request):
    record = JOBS_REGISTRY.get(job_id)
    if not record:
        raise HTTPException(status_code=404, detail="Job not found")

    base_url = str(request.base_url).rstrip("/")
    video_url = f"{base_url}/api/v1/aigc/jobs/{job_id}/video" if record.status == "completed" else None

    return {
        "jobId": record.job_id,
        "status": record.status,
        "progressPercent": record.progress_percent,
        "sha256": record.sha256,
        "videoUrl": video_url,
        "errorMessage": record.error_message,
    }


@app.get("/api/v1/aigc/jobs/{job_id}/video")
async def download_aigc_video(job_id: str, range: Optional[str] = Header(None)):
    record = JOBS_REGISTRY.get(job_id)
    if not record or record.status != "completed" or not record.output_video_path.exists():
        raise HTTPException(status_code=404, detail="Completed video not available")

    file_size = record.output_video_path.stat().st_size
    if range and range.startswith("bytes="):
        range_spec = range.replace("bytes=", "").split("-")
        start_byte = int(range_spec[0]) if range_spec[0] else 0
        end_byte = int(range_spec[1]) if (len(range_spec) > 1 and range_spec[1]) else file_size - 1
        end_byte = min(end_byte, file_size - 1)
        if start_byte >= file_size or start_byte > end_byte:
            raise HTTPException(status_code=416, detail="Requested Range Not Satisfiable")

        chunk_len = end_byte - start_byte + 1
        with record.output_video_path.open("rb") as f:
            f.seek(start_byte)
            data = f.read(chunk_len)

        headers = {
            "Content-Range": f"bytes {start_byte}-{end_byte}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(chunk_len),
            "X-Starshine-Sha256": record.sha256 or "",
        }
        return Response(content=data, status_code=206, headers=headers, media_type="video/mp4")

    return FileResponse(
        path=record.output_video_path,
        media_type="video/mp4",
        filename=f"{record.asset_id}_video_loop.mp4",
        headers={
            "Accept-Ranges": "bytes",
            "X-Starshine-Sha256": record.sha256 or "",
        },
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=SERVICE_PORT)
