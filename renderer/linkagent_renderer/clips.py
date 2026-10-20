"""只读取已验收本地片段；时间、显示和声音均来自受控工程字段。"""

import json
import math
import subprocess
from fractions import Fraction

import numpy as np
from PIL import Image

from .contract import RenderError, require


def local_input(binary, path):
    # 固定 MP4/MOV 解复用器和本地协议，视频素材不能变成网络播放列表。
    return [binary, "-v", "error", "-nostdin", "-threads", "2", "-filter_threads", "1",
            "-copyts", "-protocol_whitelist", "file,pipe", "-f", "mov", "-i", str(path)]


def inspect_clips(project, ffprobe, control):
    metadata = {}
    audio_required = {scene["clip"]["assetId"] for scene in project.document["scenes"]
                      if "clip" in scene and scene["clip"]["audioMode"] != "MUTE"}
    for key in project.video_assets:
        raw = control.run([ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe", "-f", "mov",
                           "-show_entries", "stream=codec_type,codec_name,width,height,start_time,duration,avg_frame_rate,sample_aspect_ratio,color_transfer:stream_disposition=attached_pic",
                           "-of", "json", str(project.assets[key])], True)
        streams = json.loads(raw).get("streams", [])
        videos = [s for s in streams if s.get("codec_type") == "video"]
        audios = [s for s in streams if s.get("codec_type") == "audio"]
        require(len(videos) == 1 and len(audios) <= 1, "片段需要一条画面及至多一条音轨", "INVALID_VIDEO")
        video = videos[0]
        try:
            duration, origin = float(video["duration"]), float(video.get("start_time", 0))
            rate = float(Fraction(video["avg_frame_rate"]))
            area = int(video["width"]) * int(video["height"])
            sar = float(Fraction(video.get("sample_aspect_ratio", "1:1").replace(":", "/")))
        except (KeyError, ValueError, ZeroDivisionError, OverflowError) as error:
            raise RenderError("INVALID_VIDEO", "视频元数据无法确定") from error
        require(math.isfinite(duration) and 0 < duration <= 1800 and math.isfinite(origin)
                and abs(origin) <= 1800 and 0 < area <= 8_000_000 and 0 < rate <= 60
                and math.isfinite(sar) and 0 < sar <= 16 and area * sar <= 8_000_000
                and video.get("codec_name") in ("h264", "hevc")
                and video.get("disposition", {}).get("attached_pic", 0) == 0,
                "片段尺寸、时长、帧率或编码需要先规范化", "INVALID_VIDEO")
        require(video.get("color_transfer") not in ("smpte2084", "arib-std-b67"),
                "HDR 片段需要先完成颜色规范化", "INVALID_VIDEO")
        metadata[key] = {"duration": duration, "origin": origin, "hasAudio": bool(audios)}
        if audios and key in audio_required:
            try:
                audio_start = float(audios[0].get("start_time", 0))
                audio_end = audio_start + float(audios[0]["duration"])
            except (KeyError, ValueError, OverflowError) as error:
                raise RenderError("INVALID_VIDEO", "原声音轨时间范围无法确定") from error
            require(math.isfinite(audio_start) and math.isfinite(audio_end) and audio_end > audio_start,
                    "原声音轨时间范围无效", "INVALID_VIDEO")
            metadata[key].update(audioStart=audio_start, audioEnd=audio_end)
    for scene in project.document["scenes"]:
        if "clip" not in scene:
            continue
        clip = scene["clip"]; facts = metadata[clip["assetId"]]
        require(clip["sourceEndMs"] / 1000 <= facts["duration"] + .000001, "截取区间超过视频实际时长", "CLIP_OUT_OF_RANGE")
        require(clip["audioMode"] == "MUTE" or facts["hasAudio"], "所选片段没有可保留的原声音轨", "CLIP_AUDIO_MISSING")
        if clip["audioMode"] != "MUTE":
            begin, finish = facts["origin"] + clip["sourceStartMs"] / 1000, facts["origin"] + clip["sourceEndMs"] / 1000
            require(facts["audioEnd"] > begin and facts["audioStart"] < finish,
                    "截取区间内没有可保留的原声", "CLIP_AUDIO_MISSING")
            heard_start = scene["startFrame"] * 1000 / project.fps + max(0, facts["audioStart"] - begin) * 1000
            heard_end = scene["startFrame"] * 1000 / project.fps + min(finish - begin, facts["audioEnd"] - begin) * 1000
            for cue in project.document["subtitles"]:
                if cue["sceneId"] == scene["id"] and cue["audioAssetId"] == clip["assetId"]:
                    require(cue["startMs"] >= math.floor(heard_start) and cue["endMs"] <= math.ceil(heard_end),
                            "原声字幕超过实际音轨范围", "INVALID_INPUT")
    return metadata


def interval(clip, facts):
    # 声画共用视频流起点，不能分别归零而抹掉原音轨的实际延迟。
    begin = facts["origin"] + clip["sourceStartMs"] / 1000
    finish = facts["origin"] + clip["sourceEndMs"] / 1000
    return f"{begin:.6f}", f"{finish:.6f}"


def original_audio(project, scene, facts, ffmpeg, control, samples):
    clip = scene["clip"]; begin, finish = interval(clip, facts)
    filters = f"atrim=start={begin}:end={finish},asetpts=PTS-({begin})/TB,aresample=48000:async=0:first_pts=0"
    command = local_input(ffmpeg, project.assets[clip["assetId"]]) + [
        "-map", "0:a:0", "-vn", "-af", filters, "-t", f"{samples / 48000:.9f}",
        "-ac", "2", "-ar", "48000", "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1"]
    raw = control.run(command, True, samples * 8 + 8192)
    require(len(raw) % 8 == 0, "片段音频未完整解码", "INVALID_AUDIO")
    audio = np.frombuffer(raw, dtype="<f4").reshape((-1, 2))
    require(np.isfinite(audio).all(), "片段音频含非有限数值", "INVALID_AUDIO")
    # 音轨自身结束后的静音与画面补帧不同，不拉伸原声或移动对白。
    result = np.zeros((samples, 2), dtype=np.float32)
    length = min(samples, len(audio)); result[:length] = audio[:length]
    result *= 10 ** (clip["gainDb"] / 20) * .85
    return result


class ClipFrames:
    def __init__(self, project, scene, facts, ffmpeg, control):
        self.project, self.control = project, control
        clip = scene["clip"]; begin, finish = interval(clip, facts)
        width, height = project.width, project.height
        # 直接按实际像素比例求源裁切或目标尺寸，避免窄长画面和旋转后的 SAR 放大中间帧。
        fit = (f"scale=w='max(1,min({width},{height}*iw*sar/ih))':h='max(1,min({height},{width}*ih/(iw*sar)))',"
               f"setsar=1,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2"
               if clip["fit"] == "CONTAIN" else
               f"crop=w='max(1,min(iw,ih*{width}/{height}/sar))':h='max(1,min(ih,iw*{height}/{width}*sar))':"
               f"exact=1,scale={width}:{height},setsar=1")
        filters = (f"trim=start={begin}:end={finish},setpts=PTS-({begin})/TB,"
                   f"fps={project.fps}:start_time=0:round=near,{fit}")
        command = local_input(ffmpeg, project.assets[clip["assetId"]]) + [
            "-map", "0:v:0", "-an", "-vf", filters, "-frames:v", str(scene["endFrame"] - scene["startFrame"]),
            "-threads", "2", "-pix_fmt", "rgb24", "-fps_mode", "passthrough", "-f", "rawvideo", "pipe:1"]
        self.child = control.spawn(command, stdout=subprocess.PIPE)

    def frame(self):
        self.control.check()
        size = self.project.width * self.project.height * 3
        raw = self.child.stdout.read(size)
        self.control.check()
        require(len(raw) == size, "片段实际帧不足，不能定格或循环补齐", "CLIP_FRAMES_MISSING")
        return Image.frombytes("RGB", (self.project.width, self.project.height), raw)

    def finish(self):
        require(not self.child.stdout.read(1), "片段解码帧数异常", "INVALID_VIDEO")
        self.child.wait(); self.control.check()
        require(self.child.returncode == 0, "视频片段解码失败", "INVALID_VIDEO")

    def close(self):
        self.child.stdout.close()
        if self.child.poll() is None:
            self.child.kill()
        self.child.wait()
