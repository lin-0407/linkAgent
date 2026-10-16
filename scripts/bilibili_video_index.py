"""公开统计视频指数实验版；不使用单项数量门槛。"""

import argparse
import json
from dataclasses import dataclass
from math import isfinite
from typing import Mapping

FIELDS = ("view", "like", "coin", "favorite", "reply", "danmaku")
FORMULA_VERSION = "public-stat-saturation-v1"
WEIGHTS = dict(zip(FIELDS, (0.25, 0.15, 0.30, 0.25, 0.03, 0.02)))
SCALES = dict(zip(FIELDS, (100000, 1000, 2000, 1000, 1000, 1000)))
SAMPLE_SCALE = 5000


@dataclass(frozen=True)
class VideoIndexResult:
    value: float | None
    contributions: dict[str, float]
    sample_factor: float | None
    error: str | None = None


def score_video(stats: Mapping[str, object], *, weights=None, scales=None, sample_scale=SAMPLE_SCALE):
    weights = WEIGHTS if weights is None else weights
    scales = SCALES if scales is None else scales
    # 固定刻度让同一份统计不受当天采到多少视频、案例库增删影响。
    if set(weights) != set(FIELDS) or set(scales) != set(FIELDS):
        raise ValueError("权重和刻度必须覆盖六项统计")
    if any(not isinstance(weights[key], (int, float)) or isinstance(weights[key], bool) or not isfinite(weights[key]) or weights[key] < 0 for key in FIELDS):
        raise ValueError("权重必须为有限非负数")
    if abs(sum(weights.values()) - 1) > 1e-9:
        raise ValueError("权重之和必须为1")
    if any(not isinstance(scales[key], (int, float)) or isinstance(scales[key], bool) or not isfinite(scales[key]) or scales[key] <= 0 for key in FIELDS):
        raise ValueError("刻度必须为有限正数")
    if not isinstance(sample_scale, (int, float)) or isinstance(sample_scale, bool) or not isfinite(sample_scale) or sample_scale <= 0:
        raise ValueError("规模平滑参数必须为有限正数")
    for key in FIELDS:
        count = stats.get(key)
        if not isinstance(count, int) or isinstance(count, bool) or count < 0 or count > 2**63 - 1:
            # API 缺失与真实的0不同；不能用默认0掩盖一次不完整采集。
            return VideoIndexResult(None, {}, None, f"{key}必须为非负整数且不能超过64位范围")
    parts = {key: 100 * weights[key] * stats[key] / (stats[key] + scales[key]) for key in FIELDS}
    sample_factor = stats["view"] / (stats["view"] + sample_scale)
    value = min(100.0, max(0.0, sum(parts.values()) * sample_factor))
    return VideoIndexResult(value, parts, sample_factor)


def qualifies(stats: Mapping[str, object], threshold=60.0, **score_options):
    if not isinstance(threshold, (int, float)) or isinstance(threshold, bool) or not isfinite(threshold) or not 0 <= threshold <= 100:
        raise ValueError("指数阈值必须在0至100之间")
    score = score_video(stats, **score_options)
    # 判定用原始精度，不把显示四舍五入后的60误当成严格大于60。
    return score.value is not None and score.value > threshold


def main():
    parser = argparse.ArgumentParser(description="仅依据六项公开统计计算0至100视频指数，不使用单项数量门槛")
    for key in FIELDS:
        parser.add_argument(f"--{key}", type=int, required=True)
    parser.add_argument("--min-index", type=float, default=60.0)
    args = parser.parse_args()
    stats = {key: getattr(args, key) for key in FIELDS}
    result = score_video(stats)
    try:
        accepted = qualifies(stats, args.min_index)
    except ValueError as error:
        parser.error(str(error))
    payload = {
        "formulaVersion": FORMULA_VERSION,
        "index": round(result.value, 6) if result.value is not None else None,
        "minIndex": args.min_index,
        "passesIndex": accepted,
        "sampleFactor": result.sample_factor,
        "weightedComponentsBeforeSampleFactor": result.contributions,
        "error": result.error,
    }
    print(json.dumps(payload, ensure_ascii=False))
    return 0 if result.error is None else 1


if __name__ == "__main__":
    raise SystemExit(main())
