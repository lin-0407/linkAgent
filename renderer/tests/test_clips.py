"""用实际编码片段核对截取内容、原声、混音和取消，不用文件存在代替成功。"""

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

FONT = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")


class ClipRendererTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = tempfile.TemporaryDirectory()
        cls.media = Path(cls.sources.name)
        cls.make_video(cls.media / "source.mp4")
        cls.make_video(cls.media / "portrait.mp4", "160x320")
        cls.make_video(cls.media / "no-audio.mp4", audio=False)
        cls.make_video(cls.media / "delayed.mp4", delay=True)
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(cls.media / "source.mp4"), "-c", "copy",
                        "-metadata:s:v:0", "rotate=90", "-y", str(cls.media / "rotated.mp4")], check=True, capture_output=True, timeout=15)
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(cls.media / "source.mp4"), "-c", "copy", "-output_ts_offset", "2",
                        "-y", str(cls.media / "offset.mp4")], check=True, capture_output=True, timeout=15)
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(cls.media / "source.mp4"), "-c:v", "copy", "-af", "atrim=duration=1",
                        "-c:a", "aac", "-y", str(cls.media / "short-audio.mp4")], check=True, capture_output=True, timeout=15)

    @classmethod
    def tearDownClass(cls):
        cls.sources.cleanup()

    @staticmethod
    def make_video(path, size="320x180", audio=True, delay=False):
        command = ["ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i",
                   f"color=c=red:s={size}:r=24:d=4,drawbox=color=green:t=fill:enable='gte(t,1)*lt(t,2)',drawbox=color=blue:t=fill:enable='gte(t,2)'"]
        if audio:
            if delay:
                command += ["-itsoffset", "0.25"]
            command += ["-f", "lavfi", "-i", "aevalsrc=0.2*sin(2*PI*(220+220*floor(t))*t):s=48000:d=4", "-c:a", "aac"]
        command += ["-c:v", "libx264", "-threads", "2", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", str(path)]
        subprocess.run(command, check=True, capture_output=True, timeout=20)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.video = self.root / "source.mp4"; self.video.write_bytes((self.media / "source.mp4").read_bytes())
        with wave.open(str(self.root / "voice.wav"), "wb") as voice:
            voice.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
            voice.writeframes((np.sin(np.arange(24000) * 2 * np.pi * 1000 / 24000) * 8000).astype("<i2").tobytes())
        self.document = {"schemaVersion": 2, "runId": "clip-run", "attemptId": "clip-attempt", "storyboardId": "clip-board", "seed": 7,
                         "video": {"width": 1280, "height": 720, "fps": 24, "frameCount": 48,
                                   "codec": "h264", "audioSampleRate": 48000, "fontId": "cjk_sans"},
                         "assets": [{"id": "clip", "path": "source.mp4", "mime": "video/mp4", "sha256": digest(self.video)},
                                    {"id": "voice", "path": "voice.wav", "mime": "audio/wav", "sha256": digest(self.root / "voice.wav")}],
                         "scenes": [{"id": "scene", "startFrame": 0, "endFrame": 48, "layout": "VIDEO_CLIP", "background": "#102030",
                                     "title": "真实片段", "body": [], "voice": None,
                                     "clip": {"assetId": "clip", "sourceStartMs": 1000, "sourceEndMs": 3000,
                                              "fit": "CONTAIN", "audioMode": "ORIGINAL", "gainDb": -3}}], "subtitles": []}
        self.input = self.root / "input.json"
        self.save()

    def tearDown(self):
        self.directory.cleanup()

    @property
    def scene(self):
        return self.document["scenes"][0]

    def save(self):
        self.document["inputHash"] = fingerprint(self.document)
        self.input.write_text(json.dumps(self.document, ensure_ascii=False), encoding="utf-8")

    def run_render(self, name="output"):
        self.save()
        return render(self.input, self.root, self.root / name, FONT, timeout=60)

    def assert_invalid(self, message):
        self.save()
        with self.assertRaisesRegex(RenderError, message):
            load_project(self.input, self.root)

    def narration(self, mode):
        self.scene["clip"]["audioMode"] = mode
        self.scene["voice"] = {"assetId": "voice", "offsetMs": 100, "gainDb": -3}
        self.document["subtitles"] = [{"id": "cue", "sceneId": "scene", "startMs": 100, "endMs": 1100,
                                        "text": "测试音仅用于声音验证", "audioAssetId": "voice"}]

    def replace_video(self, name):
        self.video.write_bytes((self.media / name).read_bytes())
        self.document["assets"][0]["sha256"] = digest(self.video)

    def spectrum(self, directory="output", begin=.3, length=.4):
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(self.root / directory / "video.mp4"),
                              "-ss", str(begin), "-t", str(length), "-vn", "-ar", "48000", "-ac", "1", "-f", "f32le", "pipe:1"],
                             capture_output=True, check=True, timeout=15).stdout
        values = np.frombuffer(raw, dtype="<f4")
        spectrum = np.abs(np.fft.rfft(values * np.hanning(len(values))))
        return lambda hz: float(spectrum[round(hz * len(values) / 48000)])

    def test_v2_contract_accepts_original_audio_without_inventing_subtitles(self):
        self.assertTrue(load_project(self.input, self.root).expects_audio)

    def test_v1_cannot_receive_video_fields(self):
        self.document["schemaVersion"] = 1
        self.assert_invalid("MIME")

    def test_clip_duration_cannot_change_scene_speed(self):
        self.scene["clip"]["sourceEndMs"] = 2500
        self.assert_invalid("截取时长")

    def test_unknown_filter_or_command_is_rejected(self):
        self.scene["clip"]["filter"] = "movie=https://example.invalid"
        self.assert_invalid("未支持字段")

    def test_invalid_fit_and_audio_modes_are_rejected(self):
        for field, value in (("fit", "STRETCH"), ("audioMode", "AUTO")):
            with self.subTest(field=field):
                old = self.scene["clip"][field]; self.scene["clip"][field] = value
                self.assert_invalid("模式"); self.scene["clip"][field] = old

    def test_original_audio_does_not_accidentally_mix_narration(self):
        self.narration("ORIGINAL")
        self.assert_invalid("明确选择混音")

    def test_mix_requires_narration(self):
        self.scene["clip"]["audioMode"] = "MIX"
        self.assert_invalid("需要旁白")

    def test_video_cannot_be_used_as_image(self):
        self.scene.update(layout="SPLIT_CARD", imageAssetId="clip"); self.scene.pop("clip")
        self.assert_invalid("有效图片")

    def test_subtitle_must_reference_audible_source(self):
        self.scene["clip"]["audioMode"] = "MUTE"
        self.document["subtitles"] = [{"id": "cue", "sceneId": "scene", "startMs": 0, "endMs": 1000,
                                        "text": "不能给已静音片段伪造声音字幕", "audioAssetId": "clip"}]
        self.assert_invalid("场景不存在")

    def test_out_of_range_is_checked_against_actual_video(self):
        self.scene["clip"].update(sourceStartMs=3000, sourceEndMs=5000)
        with self.assertRaisesRegex(RenderError, "实际时长"):
            self.run_render()
        self.assertFalse((self.root / "output/manifest.json").exists())

    def test_missing_original_audio_is_not_faked(self):
        self.replace_video("no-audio.mp4")
        with self.assertRaisesRegex(RenderError, "没有可保留"):
            self.run_render()
        self.assertFalse((self.root / "output/manifest.json").exists())

    def test_actual_trim_and_original_audio_match_selected_segment(self):
        result = self.run_render()
        self.assertEqual(2, result["inputSchemaVersion"])
        self.assertEqual(48, result["media"]["frameCount"])
        with Image.open(self.root / "output/frame-start.png") as first, Image.open(self.root / "output/frame-end.png") as last:
            r, g, b = first.getpixel((640, 360)); self.assertGreater(g, r + b + 60)
            r, g, b = last.getpixel((640, 360)); self.assertGreater(b, r + g + 100)
        sound = self.spectrum(); self.assertGreater(sound(440), sound(1000) * 30)
        self.assertEqual("\n", (self.root / "output/subtitles.srt").read_text())

    def test_mute_original_and_replace_with_narration(self):
        self.narration("MUTE"); self.run_render()
        sound = self.spectrum(); self.assertGreater(sound(1000), sound(440) * 30)
        self.assertIn("声音验证", (self.root / "output/subtitles.srt").read_text())

    def test_mix_contains_original_and_narration(self):
        self.narration("MIX"); self.run_render()
        sound = self.spectrum()
        self.assertGreater(sound(440), sound(1500) * 30)
        self.assertGreater(sound(1000), sound(1500) * 30)

    def test_explicit_silence_does_not_invent_voice_or_subtitles(self):
        self.scene["clip"]["audioMode"] = "MUTE"
        result = self.run_render()
        self.assertEqual(0, result["media"]["sourcePeak"])
        self.assertLessEqual(result["media"]["decodedPeak"], .0001)

    def test_contain_and_cover_apply_to_actual_portrait_frames(self):
        self.replace_video("portrait.mp4")
        for mode in ("CONTAIN", "COVER"):
            self.scene["clip"]["fit"] = mode; self.run_render(mode)
            with Image.open(self.root / mode / "frame-start.png") as frame:
                r, g, b = frame.getpixel((10, 360))
                if mode == "CONTAIN":
                    self.assertLess(r + g + b, 12)
                else:
                    self.assertGreater(g, r + b + 60)

    def test_audio_delay_is_preserved_relative_to_video(self):
        self.replace_video("delayed.mp4"); self.scene["clip"].update(sourceStartMs=0, sourceEndMs=2000)
        self.run_render()
        sound = self.spectrum(begin=.05, length=.1)
        self.assertLess(sound(220), .01)
        sound = self.spectrum(begin=.4, length=.2)
        self.assertGreater(sound(220), sound(1000) * 30)

    def test_nonzero_video_origin_uses_same_visual_and_audio_interval(self):
        self.replace_video("offset.mp4"); result = self.run_render()
        self.assertGreater(result["clipSources"]["clip"]["origin"], 1)
        with Image.open(self.root / "output/frame-start.png") as frame:
            r, g, b = frame.getpixel((640, 360)); self.assertGreater(g, r + b + 60)
        sound = self.spectrum(); self.assertGreater(sound(440), sound(1000) * 30)

    def test_rotation_metadata_changes_display_orientation(self):
        self.replace_video("rotated.mp4"); self.run_render()
        with Image.open(self.root / "output/frame-start.png") as frame:
            self.assertLess(sum(frame.getpixel((10, 360))), 12)
            r, g, b = frame.getpixel((640, 360)); self.assertGreater(g, r + b + 60)

    def test_fractional_second_cut_uses_real_source_interval(self):
        self.scene["clip"].update(sourceStartMs=500, sourceEndMs=2500); self.run_render()
        with Image.open(self.root / "output/frame-start.png") as first, Image.open(self.root / "output/frame-end.png") as last:
            r, g, b = first.getpixel((640, 360)); self.assertGreater(r, g + b + 100)
            r, g, b = last.getpixel((640, 360)); self.assertGreater(b, r + g + 100)

    def test_bad_mp4_cannot_produce_success_manifest(self):
        self.video.write_bytes(b"not-an-mp4"); self.document["assets"][0]["sha256"] = digest(self.video)
        with self.assertRaises(RenderError):
            self.run_render()
        self.assertFalse((self.root / "output/manifest.json").exists())

    def test_existing_audio_track_outside_selected_range_is_not_used(self):
        self.replace_video("short-audio.mp4"); self.scene["clip"].update(sourceStartMs=2000, sourceEndMs=4000)
        with self.assertRaisesRegex(RenderError, "区间内没有"):
            self.run_render()
        self.assertFalse((self.root / "output/manifest.json").exists())

    def test_original_subtitle_cannot_cover_delayed_audio_silence(self):
        self.replace_video("delayed.mp4"); self.scene["clip"].update(sourceStartMs=0, sourceEndMs=2000)
        self.document["subtitles"] = [{"id": "cue", "sceneId": "scene", "startMs": 10, "endMs": 100,
                                        "text": "音轨尚未开始", "audioAssetId": "clip"}]
        with self.assertRaisesRegex(RenderError, "实际音轨范围"):
            self.run_render()

    def test_missing_audio_video_can_use_explicit_narration(self):
        self.replace_video("no-audio.mp4"); self.narration("MUTE"); self.run_render()
        sound = self.spectrum(); self.assertGreater(sound(1000), sound(440) * 30)

    def test_negative_ranges_and_nonfinite_volume_are_rejected(self):
        for field, value in (("sourceStartMs", -1), ("gainDb", float("inf"))):
            with self.subTest(field=field):
                old = self.scene["clip"][field]; self.scene["clip"][field] = value
                # 非有限数值无法生成合法摘要，直接构造非法 JSON 验证读取入口拒绝。
                if field == "gainDb":
                    self.input.write_text(json.dumps(self.document), encoding="utf-8")
                else:
                    self.save()
                with self.assertRaises(RenderError):
                    load_project(self.input, self.root)
                self.scene["clip"][field] = old

    def test_original_subtitle_uses_clip_audio_identity(self):
        self.document["subtitles"] = [{"id": "cue", "sceneId": "scene", "startMs": 100, "endMs": 1000,
                                        "text": "上游核实的原声字幕", "audioAssetId": "clip"}]
        self.run_render()
        self.assertIn("原声字幕", (self.root / "output/subtitles.srt").read_text())

    def test_image_and_video_can_share_one_engine_and_timeline(self):
        second = copy.deepcopy(self.scene); second.update(id="video", startFrame=48, endFrame=96)
        self.scene.update(layout="TITLE_CARD", title="图片与片段共用时间线"); self.scene.pop("clip")
        self.document["scenes"].append(second); self.document["video"]["frameCount"] = 96
        result = self.run_render()
        self.assertEqual(96, result["media"]["frameCount"])
        with Image.open(self.root / "output/frame-end.png") as frame:
            r, g, b = frame.getpixel((640, 360)); self.assertGreater(b, r + g + 100)

    def test_cancel_stops_encoder_and_video_decoder(self):
        self.save()
        command = [sys.executable, "-m", "linkagent_renderer", "render", "--input", str(self.input), "--work-root", str(self.root),
                   "--output-dir", str(self.root / "output"), "--font", str(FONT)]
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        descendants = []
        try:
            deadline = time.monotonic() + 15
            children_file = Path(f"/proc/{child.pid}/task/{child.pid}/children")
            while time.monotonic() < deadline and child.poll() is None:
                if children_file.exists():
                    descendants = children_file.read_text().split()
                    if len(descendants) >= 2:
                        break
                time.sleep(.01)
            self.assertGreaterEqual(len(descendants), 2)
            child.send_signal(signal.SIGTERM); stdout, stderr = child.communicate(timeout=10)
            self.assertEqual(130, child.returncode, stdout + stderr)
            self.assertFalse((self.root / "output/manifest.json").exists())
            for pid in descendants:
                self.assertFalse(Path(f"/proc/{pid}").exists())
        finally:
            if child.poll() is None:
                child.kill(); child.wait()


if __name__ == "__main__":
    unittest.main()
