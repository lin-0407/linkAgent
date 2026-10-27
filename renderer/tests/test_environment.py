"""部署依赖检查真实调用本地工具，不渲染或请求供应商。"""
import json
import subprocess
import sys
import unittest
from pathlib import Path

FONT = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")


class EnvironmentTest(unittest.TestCase):
    def invoke(self, font=FONT, ffmpeg="ffmpeg"):
        return subprocess.run([sys.executable, "-m", "linkagent_renderer", "check-env", "--font", str(font), "--ffmpeg", ffmpeg], capture_output=True, text=True, timeout=15)

    def test_installed_dependencies_are_ready(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout), {"status": "READY"})

    def test_missing_font_and_binary_are_reported_without_rendering(self):
        missing_font = self.invoke(Path("/nonexistent/font.ttc"))
        self.assertNotEqual(missing_font.returncode, 0)
        self.assertEqual(json.loads(missing_font.stdout)["code"], "FONT_MISSING")
        missing_binary = self.invoke(ffmpeg="/nonexistent/ffmpeg")
        self.assertNotEqual(missing_binary.returncode, 0)
        self.assertEqual(json.loads(missing_binary.stdout)["code"], "ENVIRONMENT_MISSING")
