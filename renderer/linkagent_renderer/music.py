"""只读取已确认的音频区间；能量压低只作用于背景音乐，不声称识别了语音或完成 ASR。"""

import math
import wave

import numpy as np

from .contract import require


class AudioBed:
    """背景音乐与环境声共用同一套区间读取，差异只在是否允许随主音轨压低。"""

    def __init__(self, project, settings, duckable):
        self.project = project
        self.settings = settings
        self.duckable = duckable
        self.source = None
        self.duck_gain = 1.0
        self.total_samples = round(project.frame_count * 48000 / project.fps)
        if self.settings is not None:
            self.source = wave.open(str(project.assets[self.settings["assetId"]]), "rb")
            self.rate, self.channels = self.source.getframerate(), self.source.getnchannels()
            self.begin = round(self.settings["sourceStartMs"] * self.rate / 1000)
            self.end = round(self.settings["sourceEndMs"] * self.rate / 1000)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        if self.source is not None:
            self.source.close()

    def mix(self, main, start_sample, check):
        if self.source is None:
            return main
        check()
        samples = len(main)
        positions = self.begin + (start_sample + np.arange(samples, dtype=np.float64)) * self.rate / 48000
        valid = positions < self.end
        bed = np.zeros((samples, 2), dtype=np.float32)
        if np.any(valid):
            first = math.floor(positions[0])
            last = min(self.end, math.floor(positions[valid][-1]) + 2)
            self.source.setpos(first)
            raw = self.source.readframes(last - first)
            require(len(raw) == (last - first) * self.channels * 2,
                    "音频区间读取不完整", "INVALID_AUDIO")
            values = np.frombuffer(raw, dtype="<i2").reshape((-1, self.channels)).astype(np.float32) / 32768
            axis = np.arange(first, last, dtype=np.float64)
            for channel in range(2):
                bed[valid, channel] = np.interp(positions[valid], axis, values[:, min(channel, self.channels - 1)])
        # 仅量化容差内的尾部可静音；不读取 sourceEndMs 之外，不循环或拉伸选区。
        timeline = start_sample + np.arange(samples, dtype=np.float64)
        envelope = np.ones(samples, dtype=np.float64)
        fade_in, fade_out = self.settings["fadeInMs"] * 48, self.settings["fadeOutMs"] * 48
        if fade_in:
            envelope *= np.minimum(1, timeline / fade_in)
        if fade_out:
            envelope *= np.clip((self.total_samples - 1 - timeline) / fade_out, 0, 1)
        # 只有背景音乐允许压低；环境声是空间铺底，不随对白起伏。
        if self.duckable and self.settings.get("ducking"):
            envelope *= self.ducking(main, check)
        bed *= (envelope * 10 ** (self.settings["gainDb"] / 20) * .85)[:, None]
        check()
        return main + bed

    def ducking(self, main, check):
        envelope = np.empty(len(main), dtype=np.float64)
        # 10 毫秒实测主音轨 RMS 门限约 -46 dBFS；背景音乐本身不能触发压低。
        # 20 毫秒进入、180 毫秒恢复，状态跨场景保留，避免镜头切换时跳变。
        for start in range(0, len(main), 480):
            check()
            end = min(start + 480, len(main))
            rms = float(np.sqrt(np.mean(main[start:end].astype(np.float64) ** 2)))
            target = 10 ** (-12 / 20) if rms >= .005 else 1.0
            seconds = .02 if target < self.duck_gain else .18
            decay = np.exp(-np.arange(1, end - start + 1, dtype=np.float64) / (48000 * seconds))
            envelope[start:end] = target + (self.duck_gain - target) * decay
            self.duck_gain = float(envelope[end - 1])
        return envelope


class BackgroundMusic(AudioBed):
    def __init__(self, project):
        super().__init__(project, project.document.get("backgroundMusic"), True)


class Ambience(AudioBed):
    def __init__(self, project):
        super().__init__(project, project.document.get("ambience"), False)
