"""制作三个固定测试工程；配音由独立的本机离线脚本提供，渲染器不调用 TTS。"""

import argparse
import json
import math
import re
import sys
import wave
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from linkagent_renderer.contract import digest, fingerprint


TOPICS = {
    "rest": {
        "title": "给自己一个休息信号", "minimumSeconds": 60,
        "scenes": [
            ("忙碌不等于有效", ["先发现疲惫，再决定怎么做"], "有时候，我们已经盯着屏幕很久，却还在反复修改同一段文字。忙碌不一定代表有效，这时可以先承认自己有些累了。"),
            ("识别身体的提示", ["眼睛酸涩", "注意力开始飘走"], "眼睛酸涩，肩膀紧绷，或者注意力总是飘走，都可以作为暂停的提醒。它们不是失败的证据，只是在告诉你需要换一种节奏。"),
            ("暂停一个小片刻", [], "把手从键盘上移开，起身喝一点水，看看窗外。一个短暂的暂停，不需要复杂的仪式，也不必立刻安排另一项任务。"),
            ("整理真正重要的事", ["现在必须做", "稍后可以做"], "回来以后，把眼前的事情分成两类。一类是现在必须完成的，另一类可以稍后再做。先完成最重要的一小步，通常比同时追赶所有事情更清楚。"),
            ("给结束设置一个位置", ["写下下一步", "保存，再离开"], "准备结束时，写下下一次要做的第一件事，再把文件保存好。这样，休息就不再像突然中断，而是为下一段工作留下一个清晰的入口。"),
            ("休息也是进度的一部分", ["允许暂停，才能持续向前"], "今天没有解决的事情，可以在合适的时候继续。给自己一个休息信号，是为了让投入能够持续，而不是要求自己一直保持同样的速度。"),
        ]},
    "http": {
        "title": "一次网页请求的旅程", "minimumSeconds": 0,
        "scenes": [
            ("从一个地址开始", ["浏览器准备请求"], "在浏览器里输入一个地址，浏览器就会准备向服务器发送请求。请求会说明要访问的资源，以及这次访问需要携带的信息。"),
            ("服务器处理与响应", [], "服务器收到请求后，检查资源并执行相应的处理。随后返回状态码、响应头和内容，告诉浏览器这次请求的结果。"),
            ("从内容变成页面", ["接收响应", "绘制页面"], "浏览器收到内容后，继续解析页面需要的结构和样式。一次请求的旅程，到这里才逐渐变成我们眼前可以阅读和操作的页面。"),
        ]},
    "kitchen": {
        "title": "让厨房台面更好用", "minimumSeconds": 0,
        "scenes": [
            ("先留下一块空白", ["为日常准备留出位置"], "整理厨房时，可以先为台面留下一块固定的空白。切菜和摆放食材都有了位置，准备一顿饭的动作就会更顺手。"),
            ("按照使用频率摆放", [], "经常使用的物品放在方便拿到的地方，不常使用的收进柜子。摆放的位置跟着真实的使用习惯走，而不只是为了看起来整齐。"),
            ("让归位更容易", ["固定位置", "用完归位"], "给常用物品保留固定的位置，用完以后就能顺手放回。一个容易维持的小规则，比一次很费力的大整理，更容易留在日常生活里。"),
        ]},
}


def parts(spoken):
    sentences = [piece for piece in re.split(r"(?<=[。！？])", spoken) if piece]
    result = []
    for sentence in sentences:
        for start in range(0, len(sentence), 32):
            piece = sentence[start:start+32]
            if not any(character.isalnum() for character in piece) and result:
                result[-1] += piece
            else:
                result.append(piece)
    return result


def prepare(root):
    root.mkdir(parents=True, exist_ok=True)
    requests = []
    for name, topic in TOPICS.items():
        folder = root / name / "assets"; folder.mkdir(parents=True, exist_ok=True)
        image = Image.new("RGB", (960, 540), "#243952")
        draw = ImageDraw.Draw(image)
        if name == "rest":
            draw.ellipse((325, 90, 635, 400), fill="#f5cd79")
            draw.ellipse((395, 55, 705, 365), fill="#243952")
            draw.rectangle((100, 410, 860, 438), fill="#62c6bb")
        elif name == "http":
            for x, color in ((70, "#68b8ef"), (390, "#f5cd79"), (710, "#62c6bb")):
                draw.rounded_rectangle((x, 170, x+180, 350), radius=24, fill=color)
            for x in (265, 585):
                draw.line((x, 260, x+110, 260), fill="#f4f7fb", width=12)
                draw.polygon(((x+110, 260), (x+82, 240), (x+82, 280)), fill="#f4f7fb")
        else:
            draw.rectangle((100, 330, 860, 380), fill="#e9c79a")
            draw.rounded_rectangle((140, 195, 380, 330), radius=20, fill="#62c6bb")
            draw.ellipse((560, 190, 800, 330), fill="#f5cd79")
            draw.line((680, 150, 680, 185), fill="#62c6bb", width=16)
        image.save(folder / "illustration.png")
        for index, (_, _, spoken) in enumerate(topic["scenes"]):
            for part_index, piece in enumerate(parts(spoken)):
                requests.append({"path": f"{name}/assets/voice-{index}-part-{part_index}.wav", "text": piece})
    (root / "narration-requests.json").write_text(json.dumps(requests, ensure_ascii=False, indent=2), encoding="utf-8")


def finish(root):
    for name, topic in TOPICS.items():
        folder = root / name
        assets, scenes, subtitles, lengths, measured_cues = [], [], [], [], []
        for index in range(len(topic["scenes"])):
            audio = folder / "assets" / f"voice-{index}.wav"
            cursor_samples, cues, parameters = 0, [], None
            with wave.open(str(audio), "wb") as combined:
                for part_index, piece in enumerate(parts(topic["scenes"][index][2])):
                    segment = folder / "assets" / f"voice-{index}-part-{part_index}.wav"
                    with wave.open(str(segment), "rb") as source:
                        info = (source.getnchannels(), source.getsampwidth(), source.getframerate())
                        if parameters is None:
                            parameters = info
                            combined.setparams((*info, 0, "NONE", "not compressed"))
                        if info != parameters:
                            raise ValueError("测试语音格式不一致，不能直接拼接")
                        if part_index:
                            pause = round(info[2] * .12)
                            combined.writeframes(b"\x00" * pause * info[0] * info[1]); cursor_samples += pause
                        cues.append((piece, cursor_samples / info[2], (cursor_samples + source.getnframes()) / info[2]))
                        combined.writeframes(source.readframes(source.getnframes()))
                        cursor_samples += source.getnframes()
            measured_cues.append(cues)
            with wave.open(str(audio), "rb") as source:
                lengths.append(source.getnframes() / source.getframerate())
            assets.append({"id": f"voice-{index}", "path": f"assets/voice-{index}.wav", "sha256": digest(audio), "mime": "audio/wav"})
        picture = folder / "assets" / "illustration.png"
        assets.append({"id": "picture", "path": "assets/illustration.png", "sha256": digest(picture), "mime": "image/png"})
        frame_lengths = [math.ceil((seconds + .65) * 24) for seconds in lengths]
        minimum = topic["minimumSeconds"] * 24
        extra = max(0, minimum - sum(frame_lengths))
        for index in range(extra):
            frame_lengths[index % len(frame_lengths)] += 1
        cursor = 0
        for index, ((title, body, spoken), frames, seconds) in enumerate(zip(topic["scenes"], frame_lengths, lengths)):
            layout = "IMAGE_CARD" if not body else ("COMPARE_CARD" if title in ("整理真正重要的事", "让归位更容易") else "SPLIT_CARD")
            scene = {"id": f"scene-{index}", "startFrame": cursor, "endFrame": cursor + frames,
                     "layout": layout, "background": "#101f30", "title": title, "body": body,
                     "voice": {"assetId": f"voice-{index}", "offsetMs": 200, "gainDb": -1}}
            if layout in ("IMAGE_CARD", "SPLIT_CARD"):
                scene["imageAssetId"] = "picture"
            scenes.append(scene)
            # 字幕边界来自每一条真实离线音频的采样数，不用文字长度猜旁白时间。
            for piece_index, (piece, begin, end) in enumerate(measured_cues[index]):
                subtitles.append({"id": f"cue-{index}-{piece_index}", "sceneId": scene["id"],
                                  "startMs": math.ceil(cursor * 1000 / 24 + 200 + begin * 1000),
                                  "endMs": math.floor(cursor * 1000 / 24 + 200 + end * 1000),
                                  "text": piece, "audioAssetId": f"voice-{index}"})
            cursor += frames
        document = {"schemaVersion": 1, "runId": f"example-{name}", "attemptId": "attempt-1", "storyboardId": f"board-{name}",
                    "seed": 19, "inputHash": "", "video": {"width": 1280, "height": 720, "fps": 24, "frameCount": cursor,
                    "codec": "h264", "audioSampleRate": 48000, "fontId": "cjk_sans"}, "assets": assets, "scenes": scenes, "subtitles": subtitles}
        document["inputHash"] = fingerprint(document)
        (folder / "input.json").write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"{name}: {cursor / 24:.3f} seconds, {len(scenes)} scenes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-root", required=True, type=Path)
    parser.add_argument("--finish", action="store_true")
    args = parser.parse_args()
    (finish if args.finish else prepare)(args.work_root.resolve())
