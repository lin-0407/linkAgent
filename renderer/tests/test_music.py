"""真实 PCM 信号检查混音与选区；本地 FFmpeg 验证实际编码，不冒充供应商配乐。"""

import copy
import json
import shutil
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np

from linkagent_renderer.contract import RenderError, digest, fingerprint, load_project
from linkagent_renderer.engine import Control, render, write_audio

FONT = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")


def write_wave(path, seconds, frequency=660, amplitude=.18, rate=48000, channels=2):
    with wave.open(str(path), "wb") as audio:
        audio.setparams((channels, 2, rate, 0, "NONE", "not compressed"))
        for start in range(0, round(seconds * rate), rate):
            count = min(rate, round(seconds * rate) - start)
            values = np.sin((start + np.arange(count)) * frequency * 2 * np.pi / rate) * amplitude
            audio.writeframes(np.repeat((values * 32767).astype("<i2")[:, None], channels, axis=1).tobytes())


def read_wave(path):
    with wave.open(str(path), "rb") as audio:
        return np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2").reshape((-1, 2)).astype(np.float64) / 32768


class MusicFixture:
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        write_wave(self.root / "music.wav", 4)
        self.document = {"schemaVersion": 2, "runId": "music-run", "attemptId": "music-attempt",
                         "storyboardId": "music-board", "seed": 7,
                         "video": {"width": 1280, "height": 720, "fps": 30, "frameCount": 60,
                                   "codec": "h264", "audioSampleRate": 48000, "fontId": "cjk_sans"},
                         "assets": [{"id": "music", "path": "music.wav", "mime": "audio/wav",
                                     "sha256": digest(self.root / "music.wav")}],
                         "scenes": [{"id": "card", "startFrame": 0, "endFrame": 60, "layout": "TITLE_CARD",
                                     "background": "#102030", "title": "真实配乐检查", "body": [], "voice": None}],
                         "subtitles": [],
                         "backgroundMusic": {"assetId": "music", "sourceStartMs": 1000, "sourceEndMs": 3000,
                                             "gainDb": -6.0, "fadeInMs": 0, "fadeOutMs": 0, "ducking": False}}
        self.input = self.root / "input.json"

    def tearDown(self):
        self.directory.cleanup()

    @property
    def music(self):
        return self.document["backgroundMusic"]

    def project(self):
        self.document["inputHash"] = fingerprint(self.document)
        self.input.write_text(json.dumps(self.document, ensure_ascii=False), encoding="utf-8")
        return load_project(self.input, self.root)

    def audio(self, name="mixed.wav"):
        project = self.project()
        levels = write_audio(project, self.root / name, Control(60), {}, "ffmpeg")
        return read_wave(self.root / name), levels

    def assert_invalid(self, message):
        with self.assertRaisesRegex(RenderError, message):
            self.project()

    def add_voice(self, seconds=1, amplitude=.18):
        write_wave(self.root / "voice.wav", seconds, 440, amplitude)
        self.document["assets"].append({"id": "voice", "path": "voice.wav", "mime": "audio/wav",
                                        "sha256": digest(self.root / "voice.wav")})
        self.document["scenes"][0]["voice"] = {"assetId": "voice", "offsetMs": 500, "gainDb": 0}
        self.document["subtitles"] = [{"id": "cue", "sceneId": "card", "startMs": 500, "endMs": 1500,
                                        "text": "受控测试音，仅检查实际声音", "audioAssetId": "voice"}]


class MusicTest(MusicFixture, unittest.TestCase):
    def test_music_alone_is_a_real_audio_plan_and_selected_signal_matches(self):
        values, levels = self.audio()
        expected = np.sin((48000 + np.arange(96000)) * 660 * 2 * np.pi / 48000) * .18 * .85 * 10 ** (-6 / 20)
        self.assertEqual(96000, len(values))
        self.assertGreater(levels["sourcePeak"], .07)
        np.testing.assert_allclose(values[:, 0], expected, atol=.0001)
        self.assertTrue(self.project().expects_audio)

    def test_fades_change_actual_first_and_last_samples(self):
        baseline, _ = self.audio("baseline.wav")
        self.music.update(fadeInMs=500, fadeOutMs=500)
        faded, _ = self.audio("faded.wav")
        rms = lambda values: float(np.sqrt(np.mean(values ** 2)))
        self.assertLess(rms(faded[:4800]), rms(baseline[:4800]) * .15)
        self.assertLess(rms(faded[-4800:]), rms(baseline[-4800:]) * .15)
        np.testing.assert_array_equal(faded[30000:60000], baseline[30000:60000])
        self.assertEqual(0, faded[0, 0]); self.assertEqual(0, faded[-1, 0])

    def test_ducking_reacts_to_actual_main_audio_and_recovers_smoothly(self):
        self.add_voice()
        plain, _ = self.audio("plain.wav")
        self.music["ducking"] = True
        ducked, _ = self.audio("ducked.wav")
        # 旁白前完全一样；旁白持续时实际音乐约低 12 dB，恢复不在场景边界突变。
        np.testing.assert_array_equal(plain[:23000], ducked[:23000])
        def tone(values, frequency, start, end):
            data = values[round(start * 48000):round(end * 48000), 0]
            return abs(np.dot(data, np.exp(-2j * np.pi * frequency * np.arange(len(data)) / 48000)))
        ratio = tone(ducked, 660, .8, 1.2) / tone(plain, 660, .8, 1.2)
        self.assertAlmostEqual(.251, ratio, delta=.015)
        self.assertAlmostEqual(tone(ducked, 440, .8, 1.2), tone(plain, 440, .8, 1.2), delta=.2)
        self.assertLess(tone(ducked, 660, 1.51, 1.61), tone(ducked, 660, 1.85, 1.95))
        # 同一实际信号，切成两个场景后状态连续，输出不因剪辑边界重置。
        self.document["scenes"][0]["endFrame"] = 30
        self.document["scenes"][0]["voice"]["offsetMs"] = 0
        write_wave(self.root / "voice.wav", .5, 440, .18)
        self.document["assets"][-1]["sha256"] = digest(self.root / "voice.wav")
        self.document["subtitles"][0].update(startMs=0, endMs=500)
        second = copy.deepcopy(self.document["scenes"][0]); second.update(id="second", startFrame=30, endFrame=60, voice=None)
        self.document["scenes"].append(second)
        across, _ = self.audio("across.wav")
        self.assertGreater(tone(across, 660, 1.6, 1.9), tone(across, 660, .1, .4) * 3)

    def test_long_music_streams_across_two_scenes_without_thirty_second_voice_exception(self):
        frequency = 660.37
        write_wave(self.root / "music.wav", 40, frequency, rate=24000, channels=1)
        self.document["assets"][0]["sha256"] = digest(self.root / "music.wav")
        self.document["video"]["frameCount"] = 960
        self.document["scenes"][0]["endFrame"] = 480
        second = copy.deepcopy(self.document["scenes"][0]); second.update(id="second", startFrame=480, endFrame=960)
        self.document["scenes"].append(second)
        self.music.update(sourceStartMs=2000, sourceEndMs=34000)
        values, levels = self.audio()
        self.assertEqual(32 * 48000, len(values))
        self.assertGreater(levels["sourceRms"], .05)
        # 场景边界继续使用全片采样位置，不能把音乐开头重放一次。
        self.assertFalse(np.allclose(values[:48000], values[16 * 48000:17 * 48000]))
        expected = np.sin((18 * 48000 + np.arange(48000)) * frequency * 2 * np.pi / 48000) * .18 * .85 * 10 ** (-6 / 20)
        np.testing.assert_allclose(values[16 * 48000:17 * 48000, 0], expected, atol=.0004)

    def test_music_source_over_thirty_seconds_cannot_be_reused_as_narration(self):
        write_wave(self.root / "music.wav", 31, rate=8000, channels=1)
        self.document["assets"][0]["sha256"] = digest(self.root / "music.wav")
        self.document["scenes"][0]["voice"] = {"assetId": "music", "offsetMs": 0, "gainDb": 0}
        self.assert_invalid("不能同时作为旁白")

    def test_even_short_music_asset_has_an_exclusive_role(self):
        self.document["scenes"][0]["voice"] = {"assetId": "music", "offsetMs": 0, "gainDb": 0}
        self.assert_invalid("不能同时作为旁白")

    def test_music_limit_does_not_loosen_other_audio_asset_limit(self):
        self.add_voice(seconds=31)
        self.assert_invalid("旁白不超过 30 秒")

    def test_short_source_and_wrong_duration_are_rejected_without_looping(self):
        self.music.update(sourceStartMs=3000, sourceEndMs=5000)
        self.assert_invalid("实际范围")
        self.music.update(sourceStartMs=0, sourceEndMs=1000)
        self.assert_invalid("音乐选区时长")

    def test_only_quantization_tail_can_be_silent_without_reading_outside_selection(self):
        self.music.update(sourceStartMs=0, sourceEndMs=1980)
        values, _ = self.audio()
        self.assertGreater(np.max(np.abs(values[-4800:-1000])), .06)
        self.assertEqual(0, np.max(np.abs(values[-960:])))
        self.music["sourceEndMs"] = 1960
        self.assert_invalid("最多允许一帧")

    def test_music_gain_and_boolean_fields_are_strict_and_unknown_fields_are_rejected(self):
        for field, value in (("gainDb", 1), ("gainDb", -37), ("gainDb", True), ("fadeInMs", 10001),
                             ("fadeOutMs", -1), ("fadeInMs", True), ("ducking", 1), ("ducking", "true")):
            with self.subTest(field=field, value=value):
                old = self.music[field]; self.music[field] = value
                with self.assertRaises(RenderError): self.project()
                self.music[field] = old
        self.music["filter"] = "volume=100"
        self.assert_invalid("未支持字段")

    def test_fade_total_cannot_exceed_selected_interval(self):
        self.music.update(fadeInMs=1600, fadeOutMs=500)
        self.assert_invalid("淡入淡出总时长")

    def test_music_source_and_selection_keep_twenty_minute_import_limit(self):
        self.music.update(sourceStartMs=1198001, sourceEndMs=1200001)
        self.assert_invalid("整数超出")
        self.music.update(sourceStartMs=1000, sourceEndMs=3000)
        write_wave(self.root / "music.wav", 1201, amplitude=0, rate=8000, channels=1)
        self.document["assets"][0]["sha256"] = digest(self.root / "music.wav")
        self.assert_invalid("时长受控")

    def test_bad_music_identity_and_non_audio_cannot_be_substituted(self):
        self.music["assetId"] = "missing"
        self.assert_invalid("资产不存在")
        self.music["assetId"] = "music"
        self.document["assets"][0]["mime"] = "video/mp4"
        self.assert_invalid("真实 PCM16 WAV")

    def test_v1_rejects_even_null_music_but_v2_optional_null_keeps_silence(self):
        self.document["schemaVersion"] = 1
        self.document["backgroundMusic"] = None
        self.assert_invalid("V1 不接受")
        self.document["schemaVersion"] = 2
        self.assertFalse(self.project().expects_audio)
        values, levels = self.audio("null.wav")
        self.assertEqual(0, levels["sourcePeak"])
        self.document.pop("backgroundMusic")
        absent, _ = self.audio("absent.wav")
        np.testing.assert_array_equal(values, absent)

    def test_mixing_still_rejects_actual_clipping(self):
        write_wave(self.root / "music.wav", 4, 440, .8)
        self.document["assets"][0]["sha256"] = digest(self.root / "music.wav")
        self.add_voice(amplitude=.8)
        self.music.update(gainDb=0, ducking=False)
        with self.assertRaisesRegex(RenderError, "峰值"):
            self.audio()

    def test_music_asset_between_thirty_two_and_sixty_four_mib_is_allowed_only_as_music(self):
        # 真正的合法 WAV 超过旧 32 MiB 门槛，不能用伪造大小或假头部代替。
        write_wave(self.root / "music.wav", 180, amplitude=0)
        self.document["assets"][0]["sha256"] = digest(self.root / "music.wav")
        self.assertGreater((self.root / "music.wav").stat().st_size, 32 * 1024 ** 2)
        self.project()
        self.document["backgroundMusic"] = None
        self.assert_invalid("大小限制")


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe") and FONT.is_file(), "需要 FFmpeg 与中文字体")
class MusicNativeTest(MusicFixture, unittest.TestCase):
    def run_render(self, name):
        self.project()
        return render(self.input, self.root, self.root / name, FONT, timeout=60)

    def spectrum(self, name, start=.7, length=.4):
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(self.root / name / "video.mp4"),
                              "-ss", str(start), "-t", str(length), "-vn", "-ar", "48000", "-ac", "1", "-f", "f32le", "pipe:1"],
                             check=True, capture_output=True, timeout=15).stdout
        values = np.frombuffer(raw, dtype="<f4")
        spectrum = np.abs(np.fft.rfft(values * np.hanning(len(values))))
        return lambda hz: float(spectrum[round(hz * len(values) / 48000)])

    def test_real_music_only_long_source_has_decoded_sound_and_exact_duration(self):
        write_wave(self.root / "music.wav", 40)
        self.document["assets"][0]["sha256"] = digest(self.root / "music.wav")
        self.music.update(sourceStartMs=32000, sourceEndMs=34000, fadeInMs=250, fadeOutMs=250)
        result = self.run_render("music-only")
        self.assertEqual(60, result["media"]["frameCount"])
        self.assertEqual(2000, result["durationMs"])
        self.assertTrue(result["media"]["fullDecodePassed"])
        self.assertGreater(result["media"]["decodedPeak"], .05)
        self.assertEqual(0, result["media"]["clippedSamples"])
        tone = self.spectrum("music-only")
        self.assertGreater(tone(660), tone(440) * 30)

    def test_real_narration_and_music_ducking_preserve_both_signals(self):
        self.add_voice()
        plain = self.run_render("plain")
        self.music["ducking"] = True
        ducked = self.run_render("ducked")
        for result in (plain, ducked): self.assertTrue(result["media"]["fullDecodePassed"])
        baseline, actual = self.spectrum("plain"), self.spectrum("ducked")
        self.assertGreater(actual(440), actual(1200) * 20)
        self.assertGreater(actual(660), actual(1200) * 20)
        self.assertAlmostEqual(.251, actual(660) / baseline(660), delta=.035)
        self.assertAlmostEqual(actual(440) / baseline(440), 1, delta=.03)

    def test_real_video_original_audio_and_music_remain_in_the_same_encoded_track(self):
        source = self.root / "clip.mp4"
        subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=blue:s=240x160:r=30:d=2",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2", "-c:v", "libx264", "-threads", "2",
                        "-pix_fmt", "yuv420p", "-c:a", "aac", "-ac", "2", "-shortest", "-y", str(source)],
                       check=True, capture_output=True, timeout=20)
        self.document["assets"].append({"id": "clip", "path": "clip.mp4", "mime": "video/mp4", "sha256": digest(source)})
        self.document["scenes"][0].update(layout="VIDEO_CLIP", clip={"assetId": "clip", "sourceStartMs": 0,
            "sourceEndMs": 2000, "fit": "CONTAIN", "audioMode": "ORIGINAL", "gainDb": -3})
        self.music["ducking"] = True
        result = self.run_render("original-music")
        self.assertTrue(result["media"]["fullDecodePassed"])
        tone = self.spectrum("original-music")
        self.assertGreater(tone(440), tone(1200) * 20)
        self.assertGreater(tone(660), tone(1200) * 20)


class AmbienceTest(MusicFixture, unittest.TestCase):
    """环境声是独立铺底轨：自己的音量与淡入淡出，但不随对白压低。"""

    def add_ambience(self, frequency=220, seconds=4, start=1000, end=3000, gain=-24, fade_in=0, fade_out=0):
        write_wave(self.root / "ambience.wav", seconds, frequency)
        self.document["assets"].append({"id": "ambience", "path": "ambience.wav", "mime": "audio/wav",
                                        "sha256": digest(self.root / "ambience.wav")})
        self.document["ambience"] = {"assetId": "ambience", "sourceStartMs": start, "sourceEndMs": end,
                                     "gainDb": gain, "fadeInMs": fade_in, "fadeOutMs": fade_out}

    @staticmethod
    def tone(values, frequency, start, end):
        data = values[round(start * 48000):round(end * 48000), 0]
        # 归一化到每样本，避免不同时间窗长度直接比较时比值被样本数放大。
        return abs(np.dot(data, np.exp(-2j * np.pi * frequency * np.arange(len(data)) / 48000))) / len(data)

    def test_ambience_keeps_its_level_while_music_ducks_under_speech(self):
        self.add_voice()
        # 两路音轨都从 0 开始，才能比较同一时间窗里"有对白"与"无对白"的差别。
        self.music.update(sourceStartMs=0, sourceEndMs=2000, ducking=True)
        self.add_ambience(start=0, end=2000)
        values, _ = self.audio()
        # 0.2–0.4 秒无对白，0.8–1.2 秒有对白：环境声两段应一致，背景音乐应明显降低。
        quiet, speaking = self.tone(values, 220, .2, .4), self.tone(values, 220, .8, 1.2)
        self.assertGreater(quiet, 1e-4)
        self.assertAlmostEqual(quiet, speaking, delta=quiet * .2)
        # BGM 是 660 Hz、旁白是 440 Hz：只有 BGM 随对白降低，旁白本身不受影响。
        self.assertLess(self.tone(values, 660, .8, 1.2), self.tone(values, 660, .2, .4) * .5)
        self.assertGreater(self.tone(values, 440, .8, 1.2), 1e-3)

    def test_ambience_alone_still_counts_as_a_real_audio_plan(self):
        # 只有环境声时也必须被当成有声工程，不能被静音检查误判。
        self.document.pop("backgroundMusic")
        self.add_ambience(start=0, end=2000)
        values, levels = self.audio()
        self.assertGreater(levels["sourcePeak"], .002)
        self.assertTrue(self.project().expects_audio)
        self.assertEqual(96000, len(values))

    def test_ambience_range_must_cover_the_whole_film(self):
        self.add_ambience(end=1500)
        self.assert_invalid("环境声选区时长")

    def test_ambience_cannot_reuse_the_music_source(self):
        self.document["ambience"] = {"assetId": "music", "sourceStartMs": 0, "sourceEndMs": 2000,
                                     "gainDb": -24, "fadeInMs": 0, "fadeOutMs": 0}
        self.assert_invalid("环境声不能与背景音乐使用同一素材")

    def test_v1_rejects_ambience_and_unknown_fields_are_refused(self):
        self.add_ambience()
        self.document["schemaVersion"] = 1
        self.assert_invalid("V1 不接受背景音乐或环境声字段")
        self.document["schemaVersion"] = 2
        self.document["ambience"]["ducking"] = False
        self.assert_invalid("缺少字段或包含未支持字段")

    def test_ambience_gain_and_fades_are_strict(self):
        self.add_ambience()
        self.project()
        self.document["ambience"]["gainDb"] = 1
        self.assert_invalid("环境声音量超出范围")
        self.document["ambience"]["gainDb"] = -24
        self.document["ambience"].update(fadeInMs=1500, fadeOutMs=501)
        self.assert_invalid("环境声淡入淡出总时长不能超过选区")


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe") and FONT.is_file(), "需要 FFmpeg 与中文字体")
class AmbienceNativeTest(MusicFixture, unittest.TestCase):
    """环境声与背景音乐同时进入真实编码，解码后核对两路行为不同。"""

    def run_render(self, name):
        self.project()
        return render(self.input, self.root, self.root / name, FONT, timeout=60)

    def spectrum(self, name, start, length):
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(self.root / name / "video.mp4"),
                              "-ss", str(start), "-t", str(length), "-vn", "-ar", "48000", "-ac", "1", "-f", "f32le", "pipe:1"],
                             check=True, capture_output=True, timeout=15).stdout
        values = np.frombuffer(raw, dtype="<f4")
        spectrum = np.abs(np.fft.rfft(values * np.hanning(len(values))))
        return lambda hz: float(spectrum[round(hz * len(values) / 48000)])

    def test_real_ambience_survives_encoding_while_music_ducks(self):
        write_wave(self.root / "ambience.wav", 4, 220)
        self.document["assets"].append({"id": "ambience", "path": "ambience.wav", "mime": "audio/wav",
                                        "sha256": digest(self.root / "ambience.wav")})
        self.document["ambience"] = {"assetId": "ambience", "sourceStartMs": 0, "sourceEndMs": 2000,
                                     "gainDb": -24, "fadeInMs": 0, "fadeOutMs": 0}
        self.document["backgroundMusic"].update(sourceStartMs=0, sourceEndMs=2000, ducking=True)
        self.add_voice()
        result = self.run_render("ambience-mix")
        self.assertEqual(2000, result["durationMs"])
        self.assertTrue(result["media"]["fullDecodePassed"])
        # 旁白占 0.5–1.5 秒，比较有对白与无对白两个窗口。
        speaking, quiet = self.spectrum("ambience-mix", .6, .3), self.spectrum("ambience-mix", 1.6, .3)
        self.assertGreater(speaking(220), quiet(220) * .7)
        self.assertLess(speaking(220), quiet(220) * 1.3)
        self.assertLess(speaking(660), quiet(660) * .6)


if __name__ == "__main__":
    unittest.main()
