"""制作前只读检测本地编码与字体依赖，不读取素材或调用外部服务。"""

import subprocess
from pathlib import Path

from .contract import RenderError


def check_environment(font_path, ffmpeg, ffprobe):
    try:
        from fontTools.ttLib import TTFont
        from PIL import Image, ImageFont
    except ImportError as error:
        raise RenderError("ENVIRONMENT_MISSING", "需要安装渲染器的 Python 依赖", 3) from error
    if not Path(font_path).is_file():
        raise RenderError("FONT_MISSING", "需要部署可用的中文字体", 3)
    try:
        with TTFont(str(font_path), fontNumber=0, lazy=True) as font:
            coverage = font.getBestCmap() or {}
        if any(ord(c) not in coverage for c in "中文测试字幕"):
            raise RenderError("FONT_GLYPH_MISSING", "部署字体缺少中文字符", 3)
        ImageFont.truetype(str(font_path), 36)
        Image.new("RGB", (1, 1))
    except RenderError:
        raise
    except Exception as error:
        raise RenderError("FONT_INVALID", "中文字体无法加载", 3) from error
    try:
        for command in ([ffmpeg, "-version"], [ffprobe, "-version"]):
            subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=3)
        encoders = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], capture_output=True, check=True, timeout=3).stdout
        if b"libx264" not in encoders or b" aac " not in encoders:
            raise RenderError("ENVIRONMENT_MISSING", "FFmpeg 需要提供 H.264 和 AAC 编码器", 3)
    except (OSError, subprocess.SubprocessError) as error:
        raise RenderError("ENVIRONMENT_MISSING", "FFmpeg 或 ffprobe 不可用", 3) from error
