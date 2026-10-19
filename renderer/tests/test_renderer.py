"""真实本地编码测试与危险输入边界；测试音只验证媒体协议，不冒充旁白语义。"""

import copy
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import wave
from pathlib import Path

import numpy as np
from PIL import Image

from linkagent_renderer.contract import RenderError, digest, fingerprint, load_project
from linkagent_renderer.engine import render
from linkagent_renderer.visuals import Visuals

FONT = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")


class RendererTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        wave_path = self.root / "voice.wav"
        with wave.open(str(wave_path), "wb") as audio:
            audio.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
            samples = (np.sin(np.arange(24000) / 24000 * 440 * 2 * np.pi) * 12000).astype("<i2")
            audio.writeframes(samples.tobytes())
        picture = self.root / "picture.png"; Image.new("RGB", (320, 180), "#4f9fa3").save(picture)
        self.document = {"schemaVersion": 1, "runId": "test-run", "attemptId": "test-attempt", "storyboardId": "test-board", "seed": 7,
                         "inputHash": "", "video": {"width": 1280, "height": 720, "fps": 24, "frameCount": 48,
                         "codec": "h264", "audioSampleRate": 48000, "fontId": "cjk_sans"},
                         "assets": [{"id": "voice", "path": "voice.wav", "sha256": digest(wave_path), "mime": "audio/wav"},
                                    {"id": "picture", "path": "picture.png", "sha256": digest(picture), "mime": "image/png"}],
                         "scenes": [{"id": "scene", "startFrame": 0, "endFrame": 48, "layout": "SPLIT_CARD", "background": "#102030",
                                     "title": "本地媒体验证", "body": ["真实编码与解码"], "imageAssetId": "picture",
                                     "voice": {"assetId": "voice", "offsetMs": 100, "gainDb": -1}}],
                         "subtitles": [{"id": "cue", "sceneId": "scene", "startMs": 100, "endMs": 1100,
                                        "text": "本测试使用固定测试音", "audioAssetId": "voice"}]}
        self.input = self.root / "input.json"
        self.save()

    def tearDown(self):
        self.directory.cleanup()

    def save(self):
        self.document["inputHash"] = fingerprint(self.document)
        self.input.write_text(json.dumps(self.document, ensure_ascii=False), encoding="utf-8")

    def project(self):
        self.save()
        return load_project(self.input, self.root)

    def assert_invalid(self, code):
        with self.assertRaises(RenderError) as caught:
            self.project()
        self.assertEqual(code, caught.exception.code)

    def command(self, output="output", timeout=60):
        return [sys.executable, "-m", "linkagent_renderer", "render", "--input", str(self.input), "--work-root", str(self.root),
                "--output-dir", str(self.root / output), "--font", str(FONT), "--timeout-seconds", str(timeout)]

    def long_project(self):
        self.document["video"]["frameCount"] = 1200
        self.document["scenes"][0]["endFrame"] = 600
        second = copy.deepcopy(self.document["scenes"][0]); second.update(id="second", startFrame=600, endFrame=1200)
        self.document["scenes"].append(second)
        second_cue = copy.deepcopy(self.document["subtitles"][0]); second_cue.update(id="second-cue", sceneId="second", startMs=25100, endMs=26100)
        self.document["subtitles"].append(second_cue)
        self.save()

    def test_valid_local_contract(self):
        self.assertEqual(2000, self.project().duration_ms)

    def test_rejects_changed_input_hash(self):
        self.input.write_text(self.input.read_text().replace("本地媒体验证", "其他文本"), encoding="utf-8")
        with self.assertRaisesRegex(RenderError, "输入摘要"):
            load_project(self.input, self.root)

    def test_rejects_duplicate_json_keys(self):
        self.input.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaises(RenderError):
            load_project(self.input, self.root)

    def test_rejects_unknown_command_fields(self):
        self.document["ffmpegArgs"] = ["-i", "https://example.invalid/video"]
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_wrong_asset_hash(self):
        self.document["assets"][0]["sha256"] = "0" * 64
        self.assert_invalid("ASSET_HASH_MISMATCH")

    def test_rejects_path_traversal(self):
        self.document["assets"][0]["path"] = "../voice.wav"
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_windows_absolute_path(self):
        self.document["assets"][0]["path"] = "C:\\private\\voice.wav"
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_external_symlink(self):
        with tempfile.TemporaryDirectory() as other:
            external = Path(other) / "source.wav"; external.write_bytes((self.root / "voice.wav").read_bytes())
            link = self.root / "outside.wav"; link.symlink_to(external)
            self.document["assets"][0]["path"] = "outside.wav"
            self.assert_invalid("PATH_OUTSIDE_ROOT")

    def test_rejects_secret_file_as_asset(self):
        self.document["assets"][0]["path"] = ".env"
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_missing_image(self):
        (self.root / "picture.png").unlink()
        self.assert_invalid("ASSET_MISSING")

    def test_rejects_picture_mime_mismatch(self):
        self.document["assets"][1]["mime"] = "image/jpeg"
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_timeline_gap(self):
        self.document["scenes"][0]["startFrame"] = 1
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_audio_overflow(self):
        self.document["scenes"][0]["voice"]["offsetMs"] = 1500
        self.assert_invalid("AUDIO_OVERFLOW")

    def test_rejects_subtitle_outside_actual_audio(self):
        self.document["subtitles"][0]["endMs"] = 1500
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_zero_length_subtitle(self):
        self.document["subtitles"][0]["endMs"] = self.document["subtitles"][0]["startMs"]
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_unknown_subtitle_audio(self):
        self.document["subtitles"][0]["audioAssetId"] = "other"
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_malformed_reference_type(self):
        self.document["subtitles"][0]["sceneId"] = []
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_bright_background(self):
        self.document["scenes"][0]["background"] = "#ffffff"
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_unsupported_dimensions(self):
        self.document["video"]["width"] = 3840
        self.assert_invalid("INVALID_INPUT")

    def test_rejects_nonfinite_gain(self):
        self.document["scenes"][0]["voice"]["gainDb"] = float("nan")
        self.input.write_text(json.dumps(self.document), encoding="utf-8")
        with self.assertRaises(RenderError):
            load_project(self.input, self.root)

    def test_rejects_text_overflow_before_encoding(self):
        self.document["subtitles"][0]["text"] = "字" * 120
        with self.assertRaisesRegex(RenderError, "文字超过"):
            Visuals(self.project(), FONT)

    def test_rejects_missing_glyph(self):
        self.document["scenes"][0]["title"] = "\U0010ffff"
        with self.assertRaises(RenderError) as error:
            Visuals(self.project(), FONT)
        self.assertEqual("FONT_GLYPH_MISSING", error.exception.code)

    def test_rejects_missing_font(self):
        with self.assertRaises(RenderError) as error:
            Visuals(self.project(), self.root / "missing.ttf")
        self.assertEqual(3, error.exception.exit_code)

    def test_rejects_existing_output(self):
        output = self.root / "existing"; output.mkdir()
        with self.assertRaisesRegex(RenderError, "新目录"):
            render(self.input, self.root, output, FONT)

    def test_real_encode_decode_and_manifest(self):
        output = self.root / "completed"
        result = render(self.input, self.root, output, FONT)
        self.assertTrue(result["media"]["fullDecodePassed"])
        self.assertEqual(48, result["media"]["frameCount"])
        self.assertEqual(0, result["media"]["clippedSamples"])
        self.assertEqual(self.document["inputHash"], result["inputHash"])
        for name, artifact in result["files"].items():
            self.assertEqual(name, artifact["name"])
            self.assertEqual(digest(output / name), artifact["sha256"])
        self.assertFalse(any(output.glob("_*.wav")))
        self.assertIn("00:00:00,100 --> 00:00:01,100", (output / "subtitles.srt").read_text())

    def test_real_encoder_failure_has_no_success_manifest(self):
        result = subprocess.run(self.command() + ["--ffmpeg", "/bin/false"], capture_output=True, text=True, timeout=20)
        self.assertEqual(4, result.returncode)
        self.assertFalse((self.root / "output" / "manifest.json").exists())
        self.assertFalse((self.root / "output" / "_audio.wav").exists())

    def test_real_1080p_25fps_title_card(self):
        self.document["video"].update(width=1920, height=1080, fps=25, frameCount=50)
        self.document["scenes"][0].update(layout="TITLE_CARD", endFrame=50)
        del self.document["scenes"][0]["imageAssetId"]
        self.save()
        result = render(self.input, self.root, self.root / "full-hd", FONT)
        self.assertEqual(1920, result["media"]["width"])
        self.assertEqual(50, result["media"]["frameCount"])
        self.assertEqual(25, result["media"]["fps"])
        self.assertTrue(result["media"]["fullDecodePassed"])

    def test_timeout_stops_own_encoder_and_has_no_manifest(self):
        self.long_project()
        result = subprocess.run(self.command(timeout=1), capture_output=True, text=True, timeout=15)
        self.assertEqual(124, result.returncode, result.stdout + result.stderr)
        self.assertIn("TIMEOUT", result.stdout)
        self.assertFalse((self.root / "output" / "manifest.json").exists())

    def test_cancel_stops_own_encoder_and_has_no_manifest(self):
        self.long_project()
        child = subprocess.Popen(self.command(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 10
            while not (self.root / "output" / "_video.partial.mp4").exists() and child.poll() is None and time.monotonic() < deadline:
                time.sleep(.05)
            child.send_signal(signal.SIGTERM)
            stdout, stderr = child.communicate(timeout=10)
            self.assertEqual(130, child.returncode, stdout + stderr)
            self.assertIn("CANCELLED", stdout)
            self.assertFalse((self.root / "output" / "manifest.json").exists())
        finally:
            if child.poll() is None:
                child.kill(); child.wait()

    def test_network_and_secret_access_are_denied(self):
        script = "import sys,socket; from linkagent_renderer.__main__ import offline_audit;sys.addaudithook(offline_audit);socket.create_connection(('127.0.0.1',1))"
        result = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("渲染器禁止网络访问", result.stderr)
        script = "import sys; from linkagent_renderer.__main__ import offline_audit;sys.addaudithook(offline_audit);open('.env')"
        result = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=10)
        self.assertIn("渲染器禁止读取密钥文件", result.stderr)


if __name__ == "__main__":
    unittest.main()
