"""固定编码、流式写帧和本地质量检查；清单是最后一步发布的成功凭据。"""

import json
import math
import os
import shutil
import signal
import subprocess
import threading
import time
import wave
from pathlib import Path

import numpy as np

from . import VERSION
from .contract import RenderError, contained, digest, load_project, require
from .visuals import Visuals


class Control:
    def __init__(self, timeout):
        self.deadline = time.monotonic() + timeout
        self.cancelled = threading.Event()
        self.done = threading.Event()
        self.children = []
        self.lock = threading.Lock()
        self.log = None

    def check(self):
        if self.cancelled.is_set():
            raise RenderError("CANCELLED", "渲染已取消", 130)
        if time.monotonic() >= self.deadline:
            raise RenderError("TIMEOUT", "渲染超过本次时间限制", 124)

    def watch(self):
        while not self.done.wait(0.1):
            if self.cancelled.is_set() or time.monotonic() >= self.deadline:
                self.stop_children()
                return

    def stop_children(self):
        with self.lock:
            for child in self.children:
                if child.poll() is None:
                    child.kill()

    def spawn(self, args, stdin=None, stdout=None):
        self.check()
        # 只保留系统运行所需环境，不把部署者的数据库、OSS 或模型密钥传给编码器。
        environment = {key: value for key, value in os.environ.items()
                       if key in ("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP", "LANG", "LD_LIBRARY_PATH")}
        child = subprocess.Popen(args, stdin=stdin, stdout=stdout, stderr=self.log, env=environment,
                                 start_new_session=os.name != "nt")
        with self.lock:
            self.children.append(child)
        if self.cancelled.is_set() or time.monotonic() >= self.deadline:
            self.stop_children()
            self.check()
        return child

    def run(self, args, capture=False):
        child = self.spawn(args, stdout=subprocess.PIPE if capture else subprocess.DEVNULL)
        output = child.communicate()[0]
        self.check()
        if child.returncode:
            raise RenderError("ENCODING_FAILED", "媒体工具执行失败，详见本次渲染日志", 4)
        return output


def timestamp(milliseconds):
    hours, rest = divmod(milliseconds, 3600000)
    minutes, rest = divmod(rest, 60000)
    seconds, millis = divmod(rest, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d},{millis:03d}"


def write_audio(project, output, control):
    peak, squares, samples_count = 0.0, 0.0, 0
    with wave.open(str(output), "wb") as destination:
        destination.setparams((2, 2, 48000, 0, "NONE", "not compressed"))
        for scene in project.document["scenes"]:
            control.check()
            # 每次只持有一个场景音轨，不把整片音频和原始视频帧一起缓存。
            start_sample = round(scene["startFrame"] * 48000 / project.fps)
            end_sample = round(scene["endFrame"] * 48000 / project.fps)
            mixed = np.zeros((end_sample - start_sample, 2), dtype=np.float32)
            voice = scene["voice"]
            asset = project.assets[voice["assetId"]]
            with wave.open(str(asset), "rb") as source:
                audio = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2").reshape((-1, source.getnchannels())).astype(np.float32) / 32768
                length = round(len(audio) * 48000 / source.getframerate())
                source_axis = np.arange(len(audio), dtype=np.float64)
                target_axis = np.arange(length, dtype=np.float64) * source.getframerate() / 48000
                converted = np.column_stack([np.interp(target_axis, source_axis, audio[:, min(c, audio.shape[1] - 1)]) for c in range(2)]).astype(np.float32)
            offset = round(voice["offsetMs"] * 48)
            require(offset + len(converted) <= len(mixed), "旁白音轨量化后超出场景", "AUDIO_OVERFLOW")
            converted *= 10 ** (voice["gainDb"] / 20) * 0.85
            mixed[offset: offset + len(converted)] = converted
            current_peak = float(np.max(np.abs(mixed)))
            require(current_peak <= 0.95, "旁白超过编码峰值范围", "AUDIO_CLIPPING")
            peak = max(peak, current_peak)
            squares += float(np.sum(mixed.astype(np.float64) ** 2)); samples_count += mixed.size
            destination.writeframes((mixed * 32767).astype("<i2").tobytes())
    require(peak > 0.0001, "音轨无有效声音", "AUDIO_SILENT")
    return {"sourcePeak": round(peak, 6), "sourceRms": round(math.sqrt(squares / samples_count), 6)}


def inspect_output(project, output, ffmpeg, ffprobe, control):
    raw = control.run([ffprobe, "-v", "error", "-count_frames", "-show_streams", "-show_format", "-of", "json", str(output)], True)
    require(len(raw) < 256 * 1024, "媒体探测响应过大", "QUALITY_FAILED")
    metadata = json.loads(raw)
    streams = metadata["streams"]
    videos = [s for s in streams if s["codec_type"] == "video"]
    audios = [s for s in streams if s["codec_type"] == "audio"]
    require(len(videos) == len(audios) == 1, "输出音视频流不正确", "QUALITY_FAILED")
    video, audio = videos[0], audios[0]
    control.log.write((json.dumps({"videoDuration": video.get("duration"), "audioDuration": audio.get("duration"),
                                  "containerDuration": metadata["format"].get("duration"),
                                  "frames": video.get("nb_read_frames")}) + "\n").encode("ascii"))
    control.log.flush()
    require(video["codec_name"] == "h264" and video["pix_fmt"] == "yuv420p"
            and video["width"] == project.width and video["height"] == project.height
            and int(video["nb_read_frames"]) == project.frame_count, "实际视频参数与工程不匹配", "QUALITY_FAILED")
    numerator, denominator = video["avg_frame_rate"].split("/")
    require(int(numerator) / int(denominator) == project.fps, "实际帧率不匹配", "QUALITY_FAILED")
    expected = project.frame_count / project.fps
    require(abs(float(video["duration"]) - expected) <= 1 / project.fps + .001
            and abs(float(metadata["format"]["duration"]) - expected) <= .065
            and abs(float(audio["duration"]) - expected) <= .065
            and audio["codec_name"] == "aac" and int(audio["sample_rate"]) == 48000 and audio["channels"] == 2,
            "实际音轨或封装时长超出容差", "QUALITY_FAILED")
    # 全片解码与实际编码音轨检查独立于渲染帧和输入旁白，不用文件存在代替媒体可用。
    control.run([ffmpeg, "-v", "error", "-xerror", "-threads", "2", "-protocol_whitelist", "file,pipe",
                 "-i", str(output), "-threads", "2", "-map", "0", "-f", "null", "-"])
    decoded = output.parent / "_decoded.wav"
    control.run([ffmpeg, "-v", "error", "-nostdin", "-threads", "2", "-protocol_whitelist", "file,pipe",
                 "-i", str(output), "-vn", "-threads", "2", "-c:a", "pcm_s16le", "-y", str(decoded)])
    peak, count, squares, clipped = 0, 0, 0.0, 0
    with wave.open(str(decoded), "rb") as source:
        while chunk := source.readframes(48000):
            values = np.frombuffer(chunk, dtype="<i2").astype(np.float64) / 32768
            peak = max(peak, float(np.max(np.abs(values))))
            squares += float(np.sum(values ** 2)); count += values.size
            clipped += int(np.count_nonzero(np.abs(values) >= 32767 / 32768))
    decoded.unlink()
    require(peak > .0001 and clipped == 0, "编码音轨静音或削波", "QUALITY_FAILED")
    for name, frame in (("start", 0), ("middle", project.frame_count // 2), ("end", project.frame_count - 1)):
        control.run([ffmpeg, "-v", "error", "-nostdin", "-threads", "2", "-protocol_whitelist", "file,pipe",
                     "-i", str(output), "-vf", f"select=eq(n\\,{frame})", "-threads", "2", "-frames:v", "1", "-y", str(output.parent / f"frame-{name}.png")])
    return {"videoCodec": "h264", "audioCodec": "aac", "width": project.width, "height": project.height,
            "fps": project.fps, "frameCount": project.frame_count, "videoDurationMs": round(float(video["duration"]) * 1000),
            "containerDurationMs": round(float(metadata["format"]["duration"]) * 1000), "audioSampleRate": 48000,
            "decodedPeak": round(peak, 6), "decodedRms": round(math.sqrt(squares / count), 6), "clippedSamples": clipped,
            "fullDecodePassed": True}


def render(input_path, work_root, output_dir, font_path, ffmpeg="ffmpeg", ffprobe="ffprobe", timeout=600):
    control = Control(timeout)
    started = time.monotonic()
    project = load_project(Path(input_path), Path(work_root))
    output = contained(Path(output_dir), project.root)
    require(output != project.root and not output.exists(), "输出目录必须是本次尝试的新目录", "OUTPUT_EXISTS")
    for binary in (ffmpeg, ffprobe):
        if shutil.which(binary) is None:
            raise RenderError("ENVIRONMENT_MISSING", "缺少 FFmpeg 或 FFprobe", 3)
    visuals = Visuals(project, font_path)
    # 在启动编码前检查每个场景的实际文字布局，溢出不能拖到半片后才发现。
    try:
        for scene in project.document["scenes"]:
            preview = visuals.frame(scene, scene["startFrame"], False); preview.close()
    except Exception:
        visuals.close()
        raise
    require(shutil.disk_usage(project.root).free >= 600 * 1024**2, "工作目录可用空间不足", "DISK_SPACE_LOW")
    output.mkdir(parents=True, exist_ok=False)
    old_handlers = {}
    for sig in (signal.SIGINT, signal.SIGTERM):
        if threading.current_thread() is threading.main_thread():
            old_handlers[sig] = signal.signal(sig, lambda *_: control.cancelled.set())
    watchdog = threading.Thread(target=control.watch, name="renderer-deadline", daemon=True)
    watchdog.start()
    intermediates = ("_audio.wav", "_video.partial.mp4", "_decoded.wav", "_manifest.partial.json")
    try:
        with (output / "render.log").open("wb") as log:
            control.log = log
            log.write(f"renderer={VERSION} inputHash={project.document['inputHash']} frames={project.frame_count}\n".encode("ascii"))
            log.flush()
            levels = write_audio(project, output / "_audio.wav", control)
            cues = project.document["subtitles"]
            srt = "\n\n".join(f"{index}\n{timestamp(cue['startMs'])} --> {timestamp(cue['endMs'])}\n{cue['text']}"
                                for index, cue in enumerate(cues, 1)) + "\n"
            (output / "subtitles.srt").write_text(srt, encoding="utf-8")
            cover = visuals.frame(project.document["scenes"][0], 0, False)
            cover.save(output / "cover.png"); cover.close()
            command = [ffmpeg, "-v", "error", "-nostdin", "-threads", "2", "-filter_threads", "1",
                       "-f", "rawvideo", "-pix_fmt", "rgb24", "-s:v", f"{project.width}x{project.height}",
                       "-r", str(project.fps), "-i", "pipe:0", "-protocol_whitelist", "file,pipe",
                       "-i", str(output / "_audio.wav"), "-map", "0:v:0", "-map", "1:a:0",
                       "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-threads", "2",
                       "-pix_fmt", "yuv420p", "-c:a", "aac",
                       "-b:a", "160k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart", "-y",
                       str(output / "_video.partial.mp4")]
            child = control.spawn(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL)
            try:
                for scene in project.document["scenes"]:
                    for frame in range(scene["startFrame"], scene["endFrame"]):
                        control.check()
                        if frame % 24 == 0:
                            require((output / "render.log").stat().st_size <= 1024**2
                                    and (not (output / "_video.partial.mp4").exists()
                                         or (output / "_video.partial.mp4").stat().st_size <= 512 * 1024**2),
                                    "输出或日志超过本次大小限制", "OUTPUT_TOO_LARGE")
                        image = visuals.frame(scene, frame)
                        child.stdin.write(image.tobytes()); image.close()
                child.stdin.close()
                child.wait()
            except BrokenPipeError as error:
                control.check()
                raise RenderError("ENCODING_FAILED", "编码器提前结束，详见渲染日志", 4) from error
            control.check()
            if child.returncode:
                raise RenderError("ENCODING_FAILED", "视频编码失败，详见渲染日志", 4)
            quality = inspect_output(project, output / "_video.partial.mp4", ffmpeg, ffprobe, control)
        for asset in project.document["assets"]:
            require(digest(project.assets[asset["id"]]) == asset["sha256"], "渲染期间源资产发生变化", "ASSET_CHANGED")
        (output / "_video.partial.mp4").rename(output / "video.mp4")
        files = {}
        for name in ("video.mp4", "subtitles.srt", "cover.png", "frame-start.png", "frame-middle.png", "frame-end.png", "render.log"):
            file = output / name
            files[name] = {"name": name, "bytes": file.stat().st_size, "sha256": digest(file)}
        result = {"schemaVersion": 1, "rendererVersion": VERSION, "runId": project.document["runId"],
                  "attemptId": project.document["attemptId"], "storyboardId": project.document["storyboardId"],
                  "inputHash": project.document["inputHash"], "fontSha256": digest(Path(font_path)),
                  "durationMs": round(project.duration_ms), "elapsedMs": round((time.monotonic() - started) * 1000),
                  "media": quality | levels, "files": files,
                  "warnings": ["字幕文意由输入提供；本地检查不等同于 ASR 对齐或发布前完整试映"]}
        control.check()
        partial = output / "_manifest.partial.json"
        partial.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        partial.rename(output / "manifest.json")
        return result
    finally:
        control.done.set(); control.stop_children(); watchdog.join(timeout=2)
        for child in control.children:
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait()
        for name in intermediates:
            (output / name).unlink(missing_ok=True)
        visuals.close()
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
