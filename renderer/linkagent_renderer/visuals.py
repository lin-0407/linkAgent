"""固定图文构图与逐帧动画，文字在编码前按真实字形度量。"""

import math
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont, ImageOps

from .contract import RenderError, require


class Visuals:
    def __init__(self, project, font_path):
        self.project = project
        self.scale = project.width / 1280
        self.font_path = Path(font_path)
        if not self.font_path.is_file():
            raise RenderError("FONT_MISSING", "需要部署可用的中文字体", 3)
        try:
            with TTFont(str(self.font_path), fontNumber=0, lazy=True) as font:
                coverage = font.getBestCmap()
            self.fonts = {name: ImageFont.truetype(str(self.font_path), round(size * self.scale))
                          for name, size in (("title", 48), ("body", 30), ("subtitle", 36), ("label", 20))}
        except Exception as error:
            raise RenderError("FONT_INVALID", "中文字体无法加载", 3) from error
        visible_scenes = [s for s in project.document["scenes"] if s["layout"] != "VIDEO_CLIP"]
        # 片段标题只作元数据，不应因未绘制的文字而拒绝实际视频；字幕仍单独检查。
        texts = [s["title"] for s in visible_scenes]
        texts += [t for s in visible_scenes for t in s["body"]]
        texts += [c["text"] for c in project.document["subtitles"]]
        missing = {ord(c) for value in texts for c in value if not c.isspace() and ord(c) not in coverage}
        if missing:
            raise RenderError("FONT_GLYPH_MISSING", "字体缺少工程所需字形", 3)
        self.layouts = {}
        for scene in visible_scenes:
            if scene["layout"] == "COMPARE_CARD":
                require(len(scene["body"]) == 2, "对比构图需要两个对比项")
            title = self.wrap(scene["title"], "title", 1120, 2)
            body_width = 500 if scene["layout"] in ("SPLIT_CARD", "COMPARE_CARD") else 1080
            body = [self.wrap(item, "body", body_width, 3) for item in scene["body"]]
            require(sum(len(rows) for rows in body) <= (6 if scene["layout"] == "COMPARE_CARD" else 5), "正文无法放入安全区域", "TEXT_OVERFLOW")
            self.layouts[scene["id"]] = (title, body)
        self.cues = {cue["id"]: self.wrap(cue["text"], "subtitle", 1100, 2)
                     for cue in project.document["subtitles"]}
        self.loaded_id, self.loaded_image = None, None

    def wrap(self, text, kind, width, limit):
        measure = ImageDraw.Draw(Image.new("RGB", (1, 1)))
        rows, current = [], ""
        for character in text:
            if character == "\n":
                rows.append(current); current = ""
                continue
            if measure.textlength(current + character, font=self.fonts[kind]) > (width - 8) * self.scale:
                rows.append(current); current = character
            else:
                current += character
        if current:
            rows.append(current)
        require(rows and len(rows) <= limit, "文字超过布局安全范围，请缩短内容", "TEXT_OVERFLOW")
        return rows

    def box(self, draw, coords, color, radius=16):
        draw.rounded_rectangle(tuple(round(v * self.scale) for v in coords), radius=round(radius * self.scale), fill=color)

    def lines(self, draw, rows, kind, x, y, color="#f4f7fb", center=False):
        height = {"title": 64, "body": 44, "subtitle": 49, "label": 30}[kind]
        for row in rows:
            px = x * self.scale
            if center:
                px -= draw.textlength(row, font=self.fonts[kind]) / 2
            draw.text((round(px), round(y * self.scale)), row, font=self.fonts[kind], fill=color, anchor="lt")
            y += height
        return y

    def frame(self, scene, frame_no, subtitles=True, clip_frame=None):
        if scene["layout"] == "VIDEO_CLIP":
            # 视频保留实际画面，不套用图文标题模板；元数据标题不覆盖录屏内容。
            image = clip_frame.copy() if clip_frame is not None else Image.new("RGB", (self.project.width, self.project.height))
            if subtitles:
                self.subtitle(ImageDraw.Draw(image), frame_no)
            return image
        image = Image.new("RGB", (self.project.width, self.project.height), scene["background"])
        draw = ImageDraw.Draw(image)
        local = (frame_no - scene["startFrame"]) / max(1, scene["endFrame"] - scene["startFrame"] - 1)
        # 固定缓动只移动视觉资产，不变更旁白和字幕的时间轴。
        movement = (1 - math.cos(local * math.pi)) / 2
        phase = (frame_no / self.project.fps + self.project.document["seed"] % 17) / 2
        dot_x = round((1200 + 10 * math.sin(phase)) * self.scale)
        dot_y = round(120 * self.scale)
        draw.ellipse((dot_x, dot_y, dot_x + round(8*self.scale), dot_y + round(8*self.scale)), fill="#f0c56d")
        self.box(draw, (62, 43, 70 + 175 * movement, 50), "#f0c56d", 3)
        title, body = self.layouts[scene["id"]]
        self.lines(draw, title, "title", 80, 82)
        layout = scene["layout"]
        if layout in ("IMAGE_CARD", "SPLIT_CARD"):
            asset_id = scene["imageAssetId"]
            if self.loaded_id != asset_id:
                if self.loaded_image is not None:
                    self.loaded_image.close()
                with Image.open(self.project.assets[asset_id]) as source:
                    self.loaded_image = source.convert("RGB")
                self.loaded_id = asset_id
            bounds = (80, 248, 1200, 546) if layout == "IMAGE_CARD" else (700, 248, 1200, 550)
            x1, y1, x2, y2 = (round(v * self.scale) for v in bounds)
            fitted = ImageOps.fit(self.loaded_image, (x2-x1, y2-y1), centering=(0.45 + movement * 0.1, 0.5))
            image.paste(fitted, (x1, y1)); fitted.close()
            if layout == "IMAGE_CARD":
                require(not body, "全屏图布局的正文应放在图片或其他场景，避免遮挡")
        if layout == "COMPARE_CARD":
            for index, rows in enumerate(body):
                self.box(draw, (80 + index * 570, 265, 630 + index * 570, 540), "#182536")
                self.lines(draw, rows, "body", 106 + index * 570, 300)
        elif layout != "IMAGE_CARD":
            cursor = 270
            for index, rows in enumerate(body):
                self.box(draw, (80, cursor - 10, (640 if layout == "SPLIT_CARD" else 1200),
                                cursor + 44 * len(rows) + 8), "#182536")
                cursor = self.lines(draw, rows, "body", 102, cursor) + 24
            require(cursor <= 574, "正文会遮挡字幕安全区", "TEXT_OVERFLOW")
        if subtitles:
            self.subtitle(draw, frame_no)
        return image

    def subtitle(self, draw, frame_no):
        timestamp = frame_no * 1000 / self.project.fps
        for cue in self.project.document["subtitles"]:
            if cue["startMs"] <= timestamp < cue["endMs"]:
                rows = self.cues[cue["id"]]
                top = 618 if len(rows) == 1 else 572
                self.box(draw, (70, top - 12, 1210, top + 49 * len(rows) + 10), "#08101c", 10)
                self.lines(draw, rows, "subtitle", 640, top, center=True)
                break

    def close(self):
        if self.loaded_image is not None:
            self.loaded_image.close()
