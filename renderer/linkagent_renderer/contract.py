"""只接收固定字段和有摘要的本地资产，输入不能指定命令或滤镜。"""

import hashlib
import json
import math
import re
import struct
import wave
from dataclasses import dataclass
from pathlib import Path, PureWindowsPath

from PIL import Image

Image.MAX_IMAGE_PIXELS = 8_000_000


class RenderError(Exception):
    def __init__(self, code, message, exit_code=None):
        super().__init__(message)
        self.code = code
        self.exit_code = exit_code if exit_code is not None else {
            "QUALITY_FAILED": 4, "OUTPUT_TOO_LARGE": 4, "DISK_SPACE_LOW": 3,
        }.get(code, 2)


def require(condition, message, code="INVALID_INPUT"):
    if not condition:
        raise RenderError(code, message)


def fields(value, required, optional=()):
    require(type(value) is dict, "字段必须是对象")
    require(set(required) <= value.keys() and value.keys() <= set(required) | set(optional), "缺少字段或包含未支持字段")


def integer(value, low, high):
    require(type(value) is int and low <= value <= high, "整数超出允许范围")
    return value


def text(value, maximum):
    require(type(value) is str and 0 < len(value.strip()) <= maximum, "文字为空或超过长度限制")
    require(all(ord(c) >= 32 or c == "\n" for c in value), "文字包含控制字符")
    return value


def identifier(value):
    require(type(value) is str and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value), "业务标识不合法")
    return value


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def fingerprint(document):
    payload = {k: v for k, v in document.items() if k != "inputHash"}
    return hashlib.sha256(fingerprint_tokens(payload).encode("utf-8")).hexdigest()


def fingerprint_tokens(value, depth=0):
    if depth > 20:
        raise ValueError("工程层级过深")
    if type(value) is dict:
        return "{" + ",".join(json.dumps(key, ensure_ascii=False) + ":" + fingerprint_tokens(value[key], depth + 1)
                              for key in sorted(value)) + "}"
    if type(value) is list:
        return "[" + ",".join(fingerprint_tokens(item, depth + 1) for item in value) + "]"
    if type(value) is float:
        if not math.isfinite(value):
            raise ValueError("工程数值必须有限")
        # 裸类型标记不能与带引号的用户文字混淆，也不改变实际 JSON 中的数值类型。
        return "f64:" + struct.pack(">d", value).hex()
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def contained(path, root):
    resolved = path.resolve()
    require(resolved.is_relative_to(root), "路径越出本次工作根目录", "PATH_OUTSIDE_ROOT")
    return resolved


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, "JSON 包含重复字段")
        result[key] = value
    return result


def reject_constant(value):
    raise RenderError("INVALID_INPUT", "禁止非有限数值")


@dataclass(frozen=True)
class Project:
    document: dict
    root: Path
    assets: dict
    audio_info: dict
    width: int
    height: int
    fps: int
    frame_count: int
    video_assets: frozenset

    @property
    def duration_ms(self):
        return self.frame_count * 1000 / self.fps

    @property
    def expects_audio(self):
        return self.document.get("backgroundMusic") is not None or any(
            scene["voice"] is not None or scene.get("clip", {}).get("audioMode", "MUTE") != "MUTE"
            for scene in self.document["scenes"])


def load_project(input_path, work_root):
    root = work_root.resolve(strict=True)
    require(root.is_dir(), "工作根目录不存在")
    input_path = contained(input_path, root)
    require(input_path.is_file() and input_path.suffix.lower() == ".json", "输入必须是本地 JSON 文件")
    require(input_path.stat().st_size <= 256 * 1024, "工程清单超过大小限制")
    try:
        document = json.loads(input_path.read_text(encoding="utf-8"), object_pairs_hook=pairs,
                              parse_constant=reject_constant)
    except (ValueError, RecursionError) as error:
        raise RenderError("INVALID_INPUT", "JSON 无效或层级过深") from error
    fields(document, ("schemaVersion", "runId", "attemptId", "storyboardId", "seed", "inputHash",
                      "video", "assets", "scenes", "subtitles"), ("backgroundMusic",))
    require(type(document["schemaVersion"]) is int and document["schemaVersion"] in (1, 2), "不支持此工程版本")
    version = document["schemaVersion"]
    require(version == 2 or "backgroundMusic" not in document, "V1 不接受背景音乐字段")
    for name in ("runId", "attemptId", "storyboardId"):
        identifier(document[name])
    integer(document["seed"], 0, 2**31 - 1)
    try:
        actual_hash = fingerprint(document)
    except (ValueError, OverflowError) as error:
        raise RenderError("INVALID_INPUT", "工程数值或层级无效") from error
    require(document["inputHash"] == actual_hash, "输入摘要不匹配", "INPUT_HASH_MISMATCH")
    video = document["video"]
    fields(video, ("width", "height", "fps", "frameCount", "codec", "audioSampleRate", "fontId"))
    width = integer(video["width"], 1, 1920)
    height = integer(video["height"], 1, 1080)
    fps = integer(video["fps"], 24, 30)
    require((width, height) in ((1280, 720), (1920, 1080)) and fps in (24, 25, 30), "不支持的画幅或帧率")
    count = integer(video["frameCount"], fps, fps * 180)
    require(video["codec"] == "h264" and video["audioSampleRate"] == 48000
            and video["fontId"] == "cjk_sans", "不支持的编码或字体配置")
    music = document.get("backgroundMusic")
    music_id = None
    if music is not None:
        fields(music, ("assetId", "sourceStartMs", "sourceEndMs", "gainDb", "fadeInMs", "fadeOutMs", "ducking"))
        music_id = identifier(music["assetId"])
        begin, finish = integer(music["sourceStartMs"], 0, 1200000), integer(music["sourceEndMs"], 1, 1200000)
        require(finish > begin and abs((finish - begin) * fps - count * 1000) <= 1000,
                "音乐选区时长须对应整片，最多允许一帧误差，不循环或变速补齐")
        require(type(music["gainDb"]) in (int, float) and math.isfinite(music["gainDb"])
                and -36 <= music["gainDb"] <= 0, "音乐音量超出范围")
        integer(music["fadeInMs"], 0, 10000); integer(music["fadeOutMs"], 0, 10000)
        require(music["fadeInMs"] + music["fadeOutMs"] <= finish - begin,
                "音乐淡入淡出总时长不能超过选区")
        require(type(music["ducking"]) is bool, "音乐压低开关必须为布尔值")
    require(type(document["assets"]) is list and 0 < len(document["assets"]) <= 64, "资产数量不正确")
    assets, audio_info, video_assets = {}, {}, set()
    total_size = 0
    for asset in document["assets"]:
        fields(asset, ("id", "path", "sha256", "mime"))
        key = identifier(asset["id"])
        require(key not in assets, "资产 ID 重复")
        raw = text(asset["path"], 256)
        parts = raw.replace("\\", "/").split("/")
        require(not PureWindowsPath(raw).drive and not raw.startswith(("/", "\\"))
                and all(part and not part.startswith(".") and ":" not in part for part in parts), "资产路径不合法")
        file = contained(input_path.parent.joinpath(*parts), root)
        require(file.is_file(), "本地资产不存在", "ASSET_MISSING")
        size = file.stat().st_size
        total_size += size
        require(0 < size <= (64 if key == music_id else 32) * 1024**2
                and total_size <= 256 * 1024**2, "资产文件超出大小限制")
        require(type(asset["sha256"]) is str and re.fullmatch("[0-9a-f]{64}", asset["sha256"]), "资产摘要格式不正确")
        require(digest(file) == asset["sha256"], "资产摘要不匹配", "ASSET_HASH_MISMATCH")
        mime = asset["mime"]
        require(key != music_id or mime == "audio/wav", "背景音乐须引用真实 PCM16 WAV")
        if mime == "audio/wav":
            require(file.suffix.lower() == ".wav", "音频扩展名与类型不匹配")
            try:
                with wave.open(str(file), "rb") as audio:
                    rate, channels, samples = audio.getframerate(), audio.getnchannels(), audio.getnframes()
                    require(audio.getsampwidth() == 2 and audio.getcomptype() == "NONE"
                            and channels in (1, 2) and 8000 <= rate <= 48000
                            and 0 < samples <= rate * (1200 if key == music_id else 30),
                            "音频须为时长受控的 16 位 PCM WAV，旁白不超过 30 秒")
                    # 长音乐只逐块验收真实字节，不能把整段源 WAV 一次载入内存。
                    remaining = samples
                    while remaining:
                        chunk_frames = min(remaining, 8192)
                        require(len(audio.readframes(chunk_frames)) == chunk_frames * channels * 2, "音频数据被截断")
                        remaining -= chunk_frames
                    audio_info[key] = (rate, channels, samples)
            except (wave.Error, EOFError) as error:
                raise RenderError("INVALID_AUDIO", "音频格式无法读取") from error
        elif version == 2 and mime in ("video/mp4", "video/quicktime"):
            require(file.suffix.lower() == (".mp4" if mime == "video/mp4" else ".mov"), "视频扩展名与类型不匹配")
            video_assets.add(key)
        else:
            require(mime in ("image/png", "image/jpeg"), "不支持的资产 MIME")
            require(file.suffix.lower() in ((".png",) if mime == "image/png" else (".jpg", ".jpeg")), "图片扩展名与类型不匹配")
            try:
                with Image.open(file) as image:
                    require(image.format == ("PNG" if mime == "image/png" else "JPEG"), "图片实际格式不匹配")
                    require(0 < image.width * image.height <= 8_000_000, "图片解码尺寸过大")
                    image.verify()
            except (OSError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
                raise RenderError("INVALID_IMAGE", "图片无法安全解码") from error
        assets[key] = file
    if music is not None:
        require(music_id in audio_info, "背景音乐音频资产不存在")
        rate, _, samples = audio_info[music_id]
        require(music["sourceEndMs"] * rate <= samples * 1000,
                "音乐选区超过源音频实际范围，不能循环或补时", "MUSIC_OUT_OF_RANGE")
    scenes, cursor, scene_ids, voice_windows = document["scenes"], 0, set(), {}
    require(type(scenes) is list and 0 < len(scenes) <= 48, "场景数量不正确")
    for scene in scenes:
        fields(scene, ("id", "startFrame", "endFrame", "layout", "background", "title", "body", "voice"),
               ("imageAssetId", "clip") if version == 2 else ("imageAssetId",))
        sid = identifier(scene["id"])
        require(sid not in scene_ids, "场景 ID 重复")
        scene_ids.add(sid)
        start = integer(scene["startFrame"], 0, count - 1)
        end = integer(scene["endFrame"], 1, count)
        require(start == cursor and 0 < end - start <= fps * 30, "场景须连续且单段不超过 30 秒")
        cursor = end
        require(scene["layout"] in (("TITLE_CARD", "IMAGE_CARD", "SPLIT_CARD", "COMPARE_CARD", "VIDEO_CLIP")
                                    if version == 2 else ("TITLE_CARD", "IMAGE_CARD", "SPLIT_CARD", "COMPARE_CARD")), "不支持此布局")
        require(type(scene["background"]) is str and re.fullmatch(r"#[0-9a-fA-F]{6}", scene["background"]), "背景颜色不正确")
        channels = [int(scene["background"][n:n+2], 16) / 255 for n in (1, 3, 5)]
        linear = [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in channels]
        require(sum(c*w for c, w in zip(linear, (.2126, .7152, .0722))) <= .18, "背景过亮，会降低白色标题的可读性")
        text(scene["title"], 60)
        require(type(scene["body"]) is list and len(scene["body"]) <= 4, "正文只接受至多四项")
        for item in scene["body"]:
            text(item, 90)
        image_id = scene.get("imageAssetId")
        if image_id is not None:
            identifier(image_id)
        if scene["layout"] in ("IMAGE_CARD", "SPLIT_CARD"):
            require(image_id in assets and image_id not in audio_info and image_id not in video_assets, "图文布局需要有效图片资产")
        else:
            require(image_id is None, "此布局不接受图片资产")
        voice = scene["voice"]
        if scene["layout"] == "VIDEO_CLIP":
            clip = scene.get("clip")
            fields(clip, ("assetId", "sourceStartMs", "sourceEndMs", "fit", "audioMode", "gainDb"))
            identifier(clip["assetId"])
            require(clip["assetId"] in video_assets, "片段需要有效视频资产")
            begin, finish = integer(clip["sourceStartMs"], 0, 1800000), integer(clip["sourceEndMs"], 1, 1800000)
            require(finish > begin and abs((finish - begin) * fps - (end - start) * 1000) <= fps,
                    "视频截取时长必须对应场景，不自动变速或补帧凑时长")
            require(clip["fit"] in ("CONTAIN", "COVER") and clip["audioMode"] in ("MUTE", "ORIGINAL", "MIX"), "片段显示或声音模式不受支持")
            require(type(clip["gainDb"]) in (int, float) and math.isfinite(clip["gainDb"]) and -24 <= clip["gainDb"] <= 0,
                    "原声音量超出范围")
            require(not scene["body"], "视频画面不叠加图文正文")
            require(clip["audioMode"] != "ORIGINAL" or voice is None, "保留原声模式不能同时叠加旁白，请明确选择混音")
            require(clip["audioMode"] != "MIX" or voice is not None, "混音模式需要旁白")
            if clip["audioMode"] != "MUTE" and voice is None:
                voice_windows[sid] = (clip["assetId"], start * 1000 / fps, end * 1000 / fps)
        else:
            require("clip" not in scene, "图文场景不能夹带视频截取字段")
        if version == 2 and voice is None:
            continue
        fields(voice, ("assetId", "offsetMs", "gainDb"))
        identifier(voice["assetId"])
        require(voice["assetId"] != music_id, "背景音乐素材不能同时作为旁白")
        require(voice["assetId"] in audio_info, "旁白音频资产不存在")
        integer(voice["offsetMs"], 0, 30000)
        gain = voice["gainDb"]
        require(type(gain) in (int, float) and math.isfinite(gain) and -24 <= gain <= 0, "音频增益超出范围")
        rate, _, samples = audio_info[voice["assetId"]]
        require(samples <= rate * 30, "旁白不得复用超过 30 秒的音乐源", "AUDIO_OVERFLOW")
        voice_start = start * 1000 / fps + voice["offsetMs"]
        voice_end = voice_start + samples * 1000 / rate
        require(voice_end <= end * 1000 / fps + 0.001, "实测旁白超出场景，不能截断或偷偷加速", "AUDIO_OVERFLOW")
        voice_windows[sid] = (voice["assetId"], voice_start, voice_end)
    require(cursor == count, "场景总帧数与工程不一致")
    subtitles, last_end, cue_ids = document["subtitles"], 0, set()
    require(type(subtitles) is list and (0 if version == 2 else 1) <= len(subtitles) <= 200, "字幕数量不正确")
    covered_scenes = set()
    for cue in subtitles:
        fields(cue, ("id", "sceneId", "startMs", "endMs", "text", "audioAssetId"))
        identifier(cue["sceneId"]); identifier(cue["audioAssetId"])
        cid = identifier(cue["id"])
        require(cid not in cue_ids and cue["sceneId"] in voice_windows, "字幕 ID 重复或场景不存在")
        cue_ids.add(cid)
        begin, finish = integer(cue["startMs"], 0, 180000), integer(cue["endMs"], 1, 180000)
        asset_id, voice_start, voice_end = voice_windows[cue["sceneId"]]
        require(begin >= last_end and finish > begin and finish <= count * 1000 / fps, "字幕越界、重叠或顺序错误")
        require(cue["audioAssetId"] == asset_id and begin >= math.floor(voice_start)
                and finish <= math.ceil(voice_end), "字幕必须绑定实际旁白范围")
        text(cue["text"], 120)
        covered_scenes.add(cue["sceneId"])
        last_end = finish
    required_cues = {scene["id"] for scene in scenes if scene["voice"] is not None}
    require(required_cues <= covered_scenes, "每个旁白场景都须有明确字幕")
    return Project(document, root, assets, audio_info, width, height, fps, count, frozenset(video_assets))
