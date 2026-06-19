#!/usr/bin/env python3
"""
案例库语料离线采集脚本：抓取 B 站榜单或指定 BV 的视频信息 + 评论 + 弹幕，
直接产出「案例库导入接口」契约形状的 JSON（{source, tier, category, videos:[...]}），可原样 POST 给 /api/knowledge/reference-videos/import。

为什么单独写这个脚本、而不复用反馈脚本：
- 反馈脚本（bilibili_feedback_fetcher.py）产出的是「反馈样例」结构，和案例库导入契约不是一回事，硬塞转换反而绕；
- 案例库要的是「批量榜单」语料，这条路按项目约束只能走离线脚本（本地 cron），不能进后端后台任务；
- 故本脚本自带一套同款纯公开接口抓取内核（WBI 签名、不带 Cookie、礼貌节流），保持独立、可被后端按单 BV 直接调用，
  避免两个脚本相互 import 造成的耦合与脆弱（先跑通、不过度设计）。

合规说明：只读公开 Web 接口，不使用 Cookie、不绕过登录或风控；榜单批量供作者本地定时运行，单 BV 供后端显式触发。
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import sys
import time
import zlib
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen
from xml.etree import ElementTree


VIDEO_INFO_API = "https://api.bilibili.com/x/web-interface/view"
RANKING_API = "https://api.bilibili.com/x/web-interface/ranking/v2"
COMMENT_LIST_API = "https://api.bilibili.com/x/v2/reply"
COMMENT_WBI_MAIN_API = "https://api.bilibili.com/x/v2/reply/wbi/main"
DANMAKU_XML_API = "https://api.bilibili.com/x/v1/dm/list.so"
WBI_NAV_API = "https://api.bilibili.com/x/web-interface/nav"

COMMENT_TYPE_VIDEO = 1
DEFAULT_TIMEOUT_SECONDS = 15
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "export" / "bilibili_reference"

# 后端导入接口校验过的来源白名单；脚本侧只做默认值与提示，真正拦截仍由后端负责。
ALLOWED_SOURCES = (
    "bilibili_rank_daily",
    "bilibili_rank_weekly",
    "bilibili_rank_monthly",
    "manual_bv",
    "seed",
)
ALLOWED_TIERS = ("BENCHMARK", "COMPETITOR", "OWN_HISTORY")

# 分区中文名 → ranking/v2 的 rid（沿用 B 站榜单仍在使用的旧分区号）。
# 只列创作者最常参照的几个赛道；其余分区让作者用 --rid 直接指定，避免维护一张易过时的全量表。
CATEGORY_RID = {
    "全站": 0,
    "动画": 1,
    "音乐": 3,
    "游戏": 4,
    "娱乐": 5,
    "知识": 36,
    "科技": 188,
    "生活": 160,
    "美食": 211,
    "运动": 234,
    "影视": 181,
    "鬼畜": 119,
    "时尚": 155,
}

# B 站视频详情接口正常会返回 tname；少数视频会出现 tname 为空但 tid 仍存在。
# 这里用 tid 做有限兜底，是为了避免案例库分区为空后无法参与分区质量分和同赛道过滤。
TID_CATEGORY_FALLBACK = {
    1: "动画",
    11: "电视剧",
    13: "番剧",
    17: "单机游戏",
    19: "Mugen",
    20: "宅舞",
    21: "日常",
    22: "鬼畜调教",
    23: "电影",
    24: "MAD·AMV",
    25: "MMD·3D",
    26: "音MAD",
    27: "动画综合",
    28: "原创音乐",
    29: "音乐现场",
    30: "VOCALOID·UTAU",
    31: "翻唱",
    32: "完结动画",
    33: "连载动画",
    36: "知识",
    37: "人文·历史纪录片",
    47: "短片·手书·配音",
    51: "番剧资讯",
    59: "演奏",
    65: "网络游戏",
    71: "综艺",
    75: "动物综合",
    76: "美食制作",
    83: "其他国家电影",
    85: "短片",
    86: "特摄",
    95: "数码",
    119: "鬼畜",
    121: "GMV",
    122: "野生技能协会",
    124: "社科·法律·心理",
    126: "人力VOCALOID",
    127: "教程演示",
    129: "舞蹈",
    130: "音乐综合",
    136: "音游",
    137: "明星综合",
    138: "搞笑",
    145: "欧美电影",
    146: "日本电影",
    147: "华语电影",
    152: "官方延伸",
    153: "国产动画",
    154: "舞蹈综合",
    155: "时尚",
    156: "舞蹈教程",
    157: "美妆护肤",
    158: "穿搭",
    159: "时尚潮流",
    160: "生活",
    161: "手工",
    162: "绘画",
    164: "健身",
    167: "国创",
    168: "国产原创相关",
    169: "布袋戏",
    170: "国创资讯",
    171: "电子竞技",
    172: "手机游戏",
    173: "桌游棋牌",
    176: "汽车生活",
    177: "纪录片",
    178: "科学·探索·自然",
    179: "军事",
    180: "社会·美食·旅行",
    181: "影视",
    182: "影视杂谈",
    183: "影视剪辑",
    184: "预告·资讯",
    185: "国产剧",
    187: "海外剧",
    188: "科技",
    192: "T台",
    193: "MV",
    195: "动态漫·广播剧",
    198: "街舞",
    199: "明星舞蹈",
    200: "中国舞",
    201: "科学科普",
    202: "资讯",
    203: "热点",
    204: "环球",
    205: "社会",
    206: "资讯综合",
    207: "财经商业",
    208: "校园学习",
    209: "职业职场",
    210: "手办·模玩",
    211: "美食",
    212: "美食侦探",
    213: "美食测评",
    214: "田园美食",
    215: "美食记录",
    216: "鬼畜剧场",
    217: "动物圈",
    218: "喵星人",
    219: "汪星人",
    220: "大熊猫",
    221: "野生动物",
    222: "爬宠",
    223: "汽车",
    224: "汽车文化",
    225: "汽车极客",
    226: "智能出行",
    227: "购车攻略",
    228: "人文历史",
    230: "软件应用",
    231: "计算机技术",
    232: "科工机械",
    233: "极客DIY",
    234: "运动",
    235: "篮球",
    236: "竞技体育",
    237: "运动文化",
    238: "运动综合",
    239: "家居房产",
    240: "摩托车",
    241: "娱乐杂谈",
    242: "粉丝创作",
    243: "乐评盘点",
    244: "音乐教学",
    245: "赛车",
    246: "改装玩车",
    247: "新能源车",
    248: "房车",
    249: "足球",
    250: "风尚标",
    252: "仿妆cos",
    253: "动漫杂谈",
    254: "亲子",
    255: "游戏赛事",
    256: "小剧场",
    262: "影视整活",
}

# WBI 签名用的固定置换表（来自公开算法，非登录态）。
MIXIN_KEY_ENC_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32,
    15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19,
    29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63,
    57, 62, 11, 36, 20, 34, 44, 52,
]


class BiliApiError(RuntimeError):
    def __init__(self, message: str, code: int | None = None, url: str | None = None):
        super().__init__(message)
        self.code = code
        self.url = url


@dataclass(frozen=True)
class FetchOptions:
    source: str
    tier: str | None
    category: str | None
    max_comments: int
    max_danmaku: int
    comment_page_size: int
    comment_sort: int
    delay: float
    timeout: int


@dataclass(frozen=True)
class WbiSigner:
    mixin_key: str

    def sign(self, params: dict[str, Any]) -> dict[str, Any]:
        signed_params = dict(params)
        signed_params["wts"] = int(time.time())
        encoded_query = urlencode(
            sorted((key, sanitize_wbi_value(value)) for key, value in signed_params.items())
        )
        signed_params["w_rid"] = hashlib.md5(f"{encoded_query}{self.mixin_key}".encode("utf-8")).hexdigest()
        return signed_params


def main() -> int:
    args = parse_args()
    options = build_options(args)
    try:
        if args.mode == "rank":
            bvids, batch_label = resolve_ranking_bvids(args, options)
            output_name = f"rank_{args.rid if args.rid is not None else CATEGORY_RID.get(args.category or '全站', 0)}_reference.json"
        else:
            bvids = [extract_bvid(value) for value in args.bv]
            batch_label = options.source
            output_name = f"{bvids[0]}_reference.json" if len(bvids) == 1 else "manual_reference.json"

        payload, warnings = collect_videos(bvids, options)
        if not payload["videos"]:
            print("采集失败：没有成功采集到任何视频，无法生成导入文件。", file=sys.stderr)
            for warning in warnings:
                print(f"- {warning}", file=sys.stderr)
            return 1

        output_path = write_output(payload, resolve_output_dir(args.output_dir), output_name)
    except (BiliApiError, ValueError, OSError) as error:
        print(f"采集失败：{error}", file=sys.stderr)
        return 1

    print("采集完成：")
    print(f"- 来源：{batch_label}，视频数：{len(payload['videos'])}")
    print(f"- {output_path}")
    for warning in warnings:
        print(f"- 提示：{warning}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="抓取 B 站榜单或指定 BV 的视频与评论弹幕，产出案例库导入 JSON。",
    )

    # 两个子命令共用的通用参数：用一个无 help 的「父解析器」承载，再通过 parents= 挂到各子命令上。
    # 这样通用参数允许写在子命令之后（python ... rank --max-comments 50），避免 argparse 父子参数顺序的经典坑。
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--output-dir", default=None, help="输出目录，默认项目内 export/bilibili_reference")
    common.add_argument("--tier", default=None, help="案例层级：BENCHMARK/COMPETITOR/OWN_HISTORY，留空由后端按来源推导")
    common.add_argument("--max-comments", type=non_negative_int, default=50, help="每个视频最多采集多少条主楼评论")
    common.add_argument("--max-danmaku", type=non_negative_int, default=300, help="每个视频最多采集多少条弹幕")
    common.add_argument("--comment-page-size", type=positive_int, default=20, help="评论接口分页大小，最大 49")
    common.add_argument(
        "--comment-sort",
        choices=("0", "1", "2"),
        default="1",
        help="评论排序：0 按时间，1 按点赞数（默认，更易拿到优质评论），2 按回复数",
    )
    common.add_argument("--delay", type=non_negative_float, default=0.8, help="分页 / 视频间请求间隔秒数")
    common.add_argument("--timeout", type=positive_int, default=DEFAULT_TIMEOUT_SECONDS, help="单次请求超时秒数")

    subparsers = parser.add_subparsers(dest="mode", required=True)

    rank_parser = subparsers.add_parser("rank", parents=[common], help="抓取分区榜单的前 N 个视频")
    rank_parser.add_argument(
        "--category",
        default="全站",
        help="分区中文名（如 知识 / 科技 / 全站），用于推导榜单 rid 并标注 category",
    )
    rank_parser.add_argument(
        "--rid",
        type=non_negative_int,
        default=None,
        help="榜单分区号，显式指定时优先于 --category 推导（未知分区时用它）",
    )
    rank_parser.add_argument(
        "--source",
        choices=("bilibili_rank_daily", "bilibili_rank_weekly", "bilibili_rank_monthly"),
        default="bilibili_rank_daily",
        help="来源标签。注意 ranking/v2 是滚动综合榜，日/周/月只是溯源标注，并非真正按时间切分",
    )
    rank_parser.add_argument("--limit", type=positive_int, default=10, help="榜单中深度采集前多少个视频")

    bv_parser = subparsers.add_parser("bv", parents=[common], help="按一个或多个 BV 号采集（后端单 BV 路径复用此模式）")
    bv_parser.add_argument("bv", nargs="+", help="一个或多个 BV 号 / 视频链接")
    bv_parser.add_argument(
        "--category",
        default=None,
        help="整批默认分区标注，留空则用各视频自身分区名",
    )
    bv_parser.add_argument(
        "--source",
        choices=("manual_bv", "seed"),
        default="manual_bv",
        help="来源标签，默认 manual_bv",
    )
    return parser.parse_args()


def build_options(args: argparse.Namespace) -> FetchOptions:
    return FetchOptions(
        source=args.source,
        tier=normalize_optional(args.tier),
        category=normalize_optional(getattr(args, "category", None)),
        max_comments=args.max_comments,
        max_danmaku=args.max_danmaku,
        comment_page_size=min(args.comment_page_size, 49),
        comment_sort=int(args.comment_sort),
        delay=args.delay,
        timeout=args.timeout,
    )


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("必须是大于 0 的整数")
    return parsed


def non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("必须是大于等于 0 的整数")
    return parsed


def non_negative_float(value: str) -> float:
    parsed = float(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("必须是大于等于 0 的数字")
    return parsed


def normalize_optional(value: str | None) -> str | None:
    if value is None:
        return None
    trimmed = value.strip()
    return trimmed or None


def extract_bvid(value: str) -> str:
    matched = re.search(r"BV[0-9A-Za-z]{10}", value)
    if not matched:
        raise ValueError(f"没有从「{value}」识别到有效 BV 号")
    return matched.group(0)


def resolve_output_dir(value: str | None) -> Path:
    if value is None:
        return DEFAULT_OUTPUT_DIR
    return Path(value)


def resolve_ranking_bvids(args: argparse.Namespace, options: FetchOptions) -> tuple[list[str], str]:
    """读取榜单列表，取前 limit 个 BV 号。榜单条目本身已含部分字段，但仍逐个走视频详情接口以拿到稳定的 aid/cid。"""
    rid = args.rid if args.rid is not None else CATEGORY_RID.get(args.category, None)
    if rid is None:
        raise ValueError(f"未知分区「{args.category}」，请用 --rid 显式指定榜单分区号")

    response = fetch_json(RANKING_API, {"rid": rid, "type": "all"}, options.timeout)
    data = ensure_bili_success(response, "榜单接口")
    entries = data.get("list") if isinstance(data, dict) else None
    if not entries:
        raise BiliApiError("榜单返回为空")

    bvids: list[str] = []
    for entry in entries:
        if len(bvids) >= args.limit:
            break
        bvid = entry.get("bvid") if isinstance(entry, dict) else None
        if isinstance(bvid, str) and bvid:
            bvids.append(bvid)
    if not bvids:
        raise BiliApiError("榜单里没有解析到任何 BV 号")
    return bvids, f"{args.source}(rid={rid})"


def collect_videos(bvids: list[str], options: FetchOptions) -> tuple[dict[str, Any], list[str]]:
    """逐个 BV 采集为导入契约里的 video 条目；单个视频失败只记警告并跳过，不让整批前功尽弃。"""
    videos: list[dict[str, Any]] = []
    warnings: list[str] = []
    signer_state: dict[str, WbiSigner] = {}

    for index, bvid in enumerate(bvids):
        try:
            videos.append(build_video_item(bvid, options, warnings, signer_state))
        except (BiliApiError, ValueError) as error:
            warnings.append(f"视频 {bvid} 采集失败，已跳过：{error}")
        # 视频之间也礼貌停顿，降低被风控的概率
        if index < len(bvids) - 1:
            polite_sleep(options.delay)

    # 按 source → tier → category → videos 的顺序组装，便于人工核对导出文件
    ordered: dict[str, Any] = {"source": options.source}
    if options.tier:
        ordered["tier"] = options.tier
    if options.category:
        ordered["category"] = options.category
    ordered["videos"] = videos
    return ordered, warnings


def build_video_item(
    bvid: str,
    options: FetchOptions,
    warnings: list[str],
    signer_state: dict[str, WbiSigner],
) -> dict[str, Any]:
    video_info = fetch_video_info(bvid, options.timeout)
    stat = video_info.get("stat") if isinstance(video_info.get("stat"), dict) else {}

    comments = collect_root_comments(video_info, options, warnings, signer_state)
    danmaku = collect_danmaku(video_info, options, warnings)

    return {
        "bvId": video_info.get("bvid") or bvid,
        "title": video_info.get("title") or "",
        "description": video_info.get("desc"),
        "tags": None,  # 标签需额外接口，案例检索（5.1c+）才用到，此处先留空，保持采集轻量
        "category": resolve_video_category(video_info, warnings),
        "publishTimeText": to_publish_text(video_info.get("pubdate")),
        "stats": {
            "view": stat.get("view"),
            "like": stat.get("like"),
            "coin": stat.get("coin"),
            "favorite": stat.get("favorite"),
            "danmaku": stat.get("danmaku"),
            "reply": stat.get("reply"),
        },
        "comments": comments,
        "danmaku": danmaku,
    }


def fetch_video_info(bvid: str, timeout: int) -> dict[str, Any]:
    response = fetch_json(VIDEO_INFO_API, {"bvid": bvid}, timeout)
    data = ensure_bili_success(response, "视频信息接口")
    if not data:
        raise BiliApiError("视频信息为空")
    return data


def resolve_video_category(video_info: dict[str, Any], warnings: list[str]) -> str | None:
    tname = video_info.get("tname")
    if isinstance(tname, str) and tname.strip():
        return tname.strip()

    tid = parse_positive_int(video_info.get("tid"))
    bvid = video_info.get("bvid") or "未知 BV"
    if tid is not None:
        fallback = TID_CATEGORY_FALLBACK.get(tid, f"B站分区{tid}")
        warnings.append(f"视频 {bvid} 的 tname 为空，已用 tid={tid} 兜底为「{fallback}」")
        return fallback

    warnings.append(f"视频 {bvid} 未返回 tname/tid，分区将保持为空")
    return None


def parse_positive_int(value: Any) -> int | None:
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, str) and value.strip().isdigit():
        parsed = int(value.strip())
        return parsed if parsed > 0 else None
    return None


def collect_root_comments(
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
    signer_state: dict[str, WbiSigner],
) -> list[dict[str, Any]]:
    """只采主楼评论并裁成 {content, like, reply} 三元组：回复评论噪声大、价值低，清洗阶段也会丢，故不抓。"""
    if options.max_comments == 0:
        return []
    aid = video_info.get("aid")
    if not isinstance(aid, int):
        warnings.append(f"视频 {video_info.get('bvid')} 缺少 aid，跳过评论采集")
        return []

    try:
        replies = fetch_root_comments_by_page(aid, options)
        if not replies:
            replies = fetch_root_comments_by_cursor(aid, options, signer_state)
    except BiliApiError as error:
        warnings.append(f"视频 {video_info.get('bvid')} 评论采集失败：{error}")
        return []

    return [normalize_comment(reply) for reply in replies]


def fetch_root_comments_by_page(aid: int, options: FetchOptions) -> list[dict[str, Any]]:
    """旧版分页评论接口：多数视频可用、无需签名；拿不到时再回退到新版 cursor 接口。"""
    collected: list[dict[str, Any]] = []
    page_number = 1
    while len(collected) < options.max_comments:
        payload = fetch_json(
            COMMENT_LIST_API,
            {
                "type": COMMENT_TYPE_VIDEO,
                "oid": aid,
                "sort": options.comment_sort,
                "ps": options.comment_page_size,
                "pn": page_number,
                "nohot": 1,
            },
            options.timeout,
        )
        data = ensure_bili_success(payload, "评论列表接口")
        replies = data.get("replies") if isinstance(data, dict) else None
        if not replies:
            break
        for reply in replies:
            if len(collected) >= options.max_comments:
                break
            collected.append(reply)
        if len(replies) < options.comment_page_size:
            break
        page_number += 1
        polite_sleep(options.delay)
    return collected


def fetch_root_comments_by_cursor(
    aid: int,
    options: FetchOptions,
    signer_state: dict[str, WbiSigner],
) -> list[dict[str, Any]]:
    """新版 WBI cursor 评论接口：旧接口被限制时的兜底，签名 key 仅取自公开 nav 接口、不含登录态。"""
    collected: list[dict[str, Any]] = []
    next_offset = ""
    signer = get_wbi_signer(options.timeout, signer_state)
    while len(collected) < options.max_comments:
        payload = fetch_json(
            COMMENT_WBI_MAIN_API,
            signer.sign({
                "type": COMMENT_TYPE_VIDEO,
                "oid": aid,
                "mode": 3 if options.comment_sort else 2,
                "ps": options.comment_page_size,
                "plat": 1,
                "web_location": 1315875,
                "pagination_str": json.dumps({"offset": next_offset}, separators=(",", ":")),
            }),
            options.timeout,
        )
        data = ensure_bili_success(payload, "新版评论接口")
        replies = data.get("replies") if isinstance(data, dict) else None
        if not replies:
            break
        for reply in replies:
            if len(collected) >= options.max_comments:
                break
            collected.append(reply)
        cursor = data.get("cursor") if isinstance(data, dict) else {}
        if not isinstance(cursor, dict) or cursor.get("is_end"):
            break
        next_value = extract_next_comment_offset(cursor)
        if not next_value or next_value == next_offset:
            break
        next_offset = next_value
        polite_sleep(options.delay)
    return collected


def collect_danmaku(
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
) -> list[dict[str, Any]]:
    """只取首个分 P 的弹幕并裁成 {content, timeText}；案例库只需要弹幕语料样例，无需全分 P 全量。"""
    if options.max_danmaku == 0:
        return []
    cid = resolve_first_cid(video_info)
    if cid is None:
        warnings.append(f"视频 {video_info.get('bvid')} 缺少 cid，跳过弹幕采集")
        return []
    try:
        body = fetch_bytes(DANMAKU_XML_API, {"oid": cid}, options.timeout)
        root = ElementTree.fromstring(body)
    except (BiliApiError, ElementTree.ParseError) as error:
        warnings.append(f"视频 {video_info.get('bvid')} 弹幕采集失败：{error}")
        return []

    items: list[dict[str, Any]] = []
    for node in root.findall("d"):
        if len(items) >= options.max_danmaku:
            break
        items.append(normalize_danmaku(node))
    return items


def resolve_first_cid(video_info: dict[str, Any]) -> int | None:
    pages = video_info.get("pages")
    if isinstance(pages, list) and pages:
        first = pages[0]
        if isinstance(first, dict) and isinstance(first.get("cid"), int):
            return first["cid"]
    cid = video_info.get("cid")
    return cid if isinstance(cid, int) else None


def get_wbi_signer(timeout: int, signer_state: dict[str, WbiSigner]) -> WbiSigner:
    # 进程内缓存签名 key，避免每个视频重复请求 nav 接口
    if "signer" in signer_state:
        return signer_state["signer"]

    response = fetch_json(WBI_NAV_API, {}, timeout)
    data = response.get("data")
    if not isinstance(data, dict):
        data = ensure_bili_success(response, "WBI 签名信息接口")
    wbi_img = data.get("wbi_img") if isinstance(data, dict) else None
    if not isinstance(wbi_img, dict):
        raise BiliApiError("WBI 签名信息接口没有返回 wbi_img")

    img_key = extract_url_stem(wbi_img.get("img_url"))
    sub_key = extract_url_stem(wbi_img.get("sub_url"))
    raw_key = f"{img_key}{sub_key}"
    if len(raw_key) < max(MIXIN_KEY_ENC_TAB) + 1:
        raise BiliApiError("WBI 签名 key 长度异常")

    mixin_key = "".join(raw_key[index] for index in MIXIN_KEY_ENC_TAB)[:32]
    signer = WbiSigner(mixin_key)
    signer_state["signer"] = signer
    return signer


def extract_url_stem(value: Any) -> str:
    if not isinstance(value, str) or not value:
        raise BiliApiError("WBI 签名图片地址为空")
    return Path(urlparse(value).path).stem


def sanitize_wbi_value(value: Any) -> str:
    return "".join(char for char in str(value) if char not in "!'()*")


def extract_next_comment_offset(cursor: dict[str, Any]) -> str:
    pagination = cursor.get("pagination_reply") if isinstance(cursor, dict) else None
    if isinstance(pagination, dict) and pagination.get("next_offset"):
        return str(pagination["next_offset"])
    next_value = cursor.get("next") if isinstance(cursor, dict) else None
    if isinstance(next_value, int):
        return str(next_value)
    return ""


def fetch_json(url: str, params: dict[str, Any], timeout: int) -> dict[str, Any]:
    body = fetch_bytes(url, params, timeout)
    return json.loads(body.decode("utf-8"))


def fetch_bytes(url: str, params: dict[str, Any], timeout: int) -> bytes:
    full_url = f"{url}?{urlencode(params)}"
    request = Request(
        full_url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/125.0 Safari/537.36"
            ),
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://www.bilibili.com/",
        },
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
            encoding = response.headers.get("Content-Encoding", "").lower()
    except HTTPError as error:
        raise BiliApiError(f"HTTP {error.code}: {full_url}", url=full_url) from error
    except URLError as error:
        raise BiliApiError(f"网络请求失败：{error.reason}") from error
    return decode_response_body(body, encoding)


def decode_response_body(body: bytes, encoding: str) -> bytes:
    if encoding == "gzip":
        return gzip.decompress(body)
    if encoding == "deflate":
        try:
            return zlib.decompress(body)
        except zlib.error:
            return zlib.decompress(body, -zlib.MAX_WBITS)
    return body


def ensure_bili_success(response: dict[str, Any], api_name: str) -> Any:
    code = response.get("code")
    if code == 0:
        return response.get("data")
    message = response.get("message") or response.get("msg") or "未知错误"
    if code in (-101, -352, -403):
        raise BiliApiError(f"{api_name} 被限制访问：{message}。脚本不会使用 Cookie 或绕过风控。", code=code)
    if code == 12002:
        raise BiliApiError(f"{api_name} 对应评论区已关闭", code=code)
    raise BiliApiError(f"{api_name} 返回错误 code={code}, message={message}", code=code)


def normalize_comment(reply: dict[str, Any]) -> dict[str, Any]:
    content = reply.get("content") if isinstance(reply.get("content"), dict) else {}
    return {
        "content": content.get("message", ""),
        "like": reply.get("like"),
        "reply": reply.get("rcount"),
    }


def normalize_danmaku(node: ElementTree.Element) -> dict[str, Any]:
    parts = (node.attrib.get("p") or "").split(",")
    progress_seconds = parse_float_at(parts, 0)
    return {
        "content": node.text or "",
        "timeText": seconds_to_timestamp(progress_seconds),
    }


def parse_float_at(parts: list[str], index: int) -> float | None:
    if index >= len(parts) or parts[index] == "":
        return None
    try:
        return round(float(parts[index]), 3)
    except ValueError:
        return None


def seconds_to_timestamp(seconds: float | None) -> str:
    if seconds is None:
        return ""
    total_seconds = int(seconds)
    minutes, second = divmod(total_seconds, 60)
    hour, minute = divmod(minutes, 60)
    if hour:
        return f"{hour:02d}:{minute:02d}:{second:02d}"
    return f"{minute:02d}:{second:02d}"


def to_publish_text(timestamp: Any) -> str | None:
    # B 站 pubdate 是服务器（东八区）时间戳，按 UTC+8 格式化成可读文本，避免随运行机器时区漂移
    if not isinstance(timestamp, int) or timestamp <= 0:
        return None
    china_time = datetime.fromtimestamp(timestamp, timezone(timedelta(hours=8)))
    return china_time.strftime("%Y-%m-%d %H:%M")


def polite_sleep(delay: float) -> None:
    if delay > 0:
        time.sleep(delay)


def write_output(payload: dict[str, Any], output_dir: Path, output_name: str) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / output_name
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return output_path


if __name__ == "__main__":
    raise SystemExit(main())
