#!/usr/bin/env python3
"""
给定 B 站 BV 号，拉取公开评论样例和弹幕样例，并输出为本地文件。

本脚本只做单视频、限量、本地辅助采集；不接收 Cookie，不绕过登录或风控。
这样做是为了让项目保持“用户主动指定视频并生成样例数据”的边界，而不是做后台爬取系统。
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
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen
from xml.etree import ElementTree


VIDEO_INFO_API = "https://api.bilibili.com/x/web-interface/view"
COMMENT_LIST_API = "https://api.bilibili.com/x/v2/reply"
COMMENT_WBI_MAIN_API = "https://api.bilibili.com/x/v2/reply/wbi/main"
COMMENT_WBI_REPLY_API = "https://api.bilibili.com/x/v2/reply/wbi/reply"
COMMENT_REPLY_API = "https://api.bilibili.com/x/v2/reply/reply"
DANMAKU_XML_API = "https://api.bilibili.com/x/v1/dm/list.so"
WBI_NAV_API = "https://api.bilibili.com/x/web-interface/nav"

COMMENT_TYPE_VIDEO = 1
DEFAULT_TIMEOUT_SECONDS = 15
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "exports" / "bilibili_feedback"
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
_WBI_SIGNER_CACHE: WbiSigner | None = None


class BiliApiError(RuntimeError):
    def __init__(self, message: str, code: int | None = None, url: str | None = None):
        super().__init__(message)
        self.code = code
        self.url = url


@dataclass(frozen=True)
class FetchOptions:
    bvid: str
    output_dir: Path
    output_format: str
    page: int
    all_pages: bool
    max_comments: int
    max_replies_per_comment: int
    max_danmaku: int
    comment_page_size: int
    reply_page_size: int
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
    try:
        options = FetchOptions(
            bvid=extract_bvid(args.bv),
            output_dir=resolve_output_dir(args.output_dir),
            output_format=args.format,
            page=args.page,
            all_pages=args.all_pages,
            max_comments=args.max_comments,
            max_replies_per_comment=args.max_replies_per_comment,
            max_danmaku=args.max_danmaku,
            comment_page_size=min(args.comment_page_size, 49),
            reply_page_size=min(args.reply_page_size, 20),
            comment_sort=int(args.comment_sort),
            delay=args.delay,
            timeout=args.timeout,
        )
        payload = collect_feedback(options)
        output_paths = write_outputs(payload, options)
    except (BiliApiError, ValueError, OSError) as error:
        print(f"采集失败：{error}", file=sys.stderr)
        return 1

    print("采集完成：")
    for output_path in output_paths:
        print(f"- {output_path}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="按 BV 号拉取 B 站公开视频的评论与弹幕样例，并区分主楼评论和回复评论。",
    )
    parser.add_argument("bv", help="BV 号或包含 BV 号的视频链接")
    parser.add_argument(
        "--output-dir",
        default=None,
        help="输出目录，默认项目内 exports/bilibili_feedback",
    )
    parser.add_argument(
        "--format",
        choices=("json", "txt", "both"),
        default="both",
        help="输出格式，默认同时输出 JSON 和 TXT",
    )
    parser.add_argument("--page", type=positive_int, default=1, help="只拉取指定分 P 的弹幕，默认第 1P")
    parser.add_argument("--all-pages", action="store_true", help="拉取所有分 P 的弹幕")
    parser.add_argument("--max-comments", type=non_negative_int, default=50, help="最多拉取多少条主楼评论")
    parser.add_argument(
        "--max-replies-per-comment",
        type=non_negative_int,
        default=20,
        help="每条主楼评论最多拉取多少条回复评论",
    )
    parser.add_argument("--max-danmaku", type=non_negative_int, default=500, help="每个分 P 最多拉取多少条弹幕")
    parser.add_argument("--comment-page-size", type=positive_int, default=20, help="评论接口分页大小，最大 49")
    parser.add_argument("--reply-page-size", type=positive_int, default=20, help="回复接口分页大小，最大 20")
    parser.add_argument(
        "--comment-sort",
        choices=("0", "1", "2"),
        default="0",
        help="评论排序：0 按时间，1 按点赞数，2 按回复数",
    )
    parser.add_argument("--delay", type=non_negative_float, default=0.8, help="分页请求间隔秒数")
    parser.add_argument("--timeout", type=positive_int, default=DEFAULT_TIMEOUT_SECONDS, help="单次请求超时秒数")
    return parser.parse_args()


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


def extract_bvid(value: str) -> str:
    matched = re.search(r"BV[0-9A-Za-z]{10}", value)
    if not matched:
        raise ValueError("没有识别到有效 BV 号")
    return matched.group(0)


def resolve_output_dir(value: str | None) -> Path:
    if value is None:
        return DEFAULT_OUTPUT_DIR
    return Path(value)


def collect_feedback(options: FetchOptions) -> dict[str, Any]:
    warnings: list[str] = []
    video_info = fetch_video_info(options.bvid, options.timeout)
    pages = select_pages(video_info, options)

    comments = collect_comments(video_info, options, warnings)
    danmaku = collect_danmaku_pages(pages, options, warnings)

    return {
        "schemaVersion": "1.0",
        "source": "bilibili_public_web",
        "fetchedAt": now_iso(),
        "request": {
            "bvid": options.bvid,
            "page": options.page,
            "allPages": options.all_pages,
            "maxComments": options.max_comments,
            "maxRepliesPerComment": options.max_replies_per_comment,
            "maxDanmakuPerPage": options.max_danmaku,
            "commentSort": options.comment_sort,
        },
        "warnings": warnings,
        "video": normalize_video(video_info, pages),
        "comments": comments,
        "danmaku": danmaku,
    }


def fetch_video_info(bvid: str, timeout: int) -> dict[str, Any]:
    response = fetch_json(VIDEO_INFO_API, {"bvid": bvid}, timeout)
    data = ensure_bili_success(response, "视频信息接口")
    if not data:
        raise BiliApiError("视频信息为空")
    return data


def collect_comments(
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
) -> dict[str, Any]:
    if options.max_comments == 0:
        return empty_comments("用户设置 max-comments=0")

    aid = video_info.get("aid")
    if not isinstance(aid, int):
        return empty_comments("视频 aid 不存在，无法读取评论区")

    try:
        root_comments, reported_total = fetch_root_comments(aid, video_info, options, warnings)
    except BiliApiError as error:
        warnings.append(f"评论读取失败：{error}")
        return empty_comments(str(error))

    reply_count = sum(len(item["replyComments"]) for item in root_comments)
    return {
        "oid": aid,
        "type": COMMENT_TYPE_VIDEO,
        "reportedTotal": reported_total,
        "rootCount": len(root_comments),
        "replyCount": reply_count,
        "rootComments": root_comments,
    }


def fetch_root_comments(
    aid: int,
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
) -> tuple[list[dict[str, Any]], int | None]:
    root_comments, reported_total = fetch_root_comments_by_page(aid, video_info, options, warnings)
    if root_comments:
        return root_comments, reported_total

    stat = video_info.get("stat") if isinstance(video_info.get("stat"), dict) else {}
    if reported_total == 0 and not stat.get("reply"):
        return root_comments, reported_total

    warnings.append("旧评论分页接口没有返回主楼评论，已尝试切换到新版 cursor 评论接口。")
    return fetch_root_comments_by_cursor(aid, video_info, options, warnings)


def fetch_root_comments_by_page(
    aid: int,
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
) -> tuple[list[dict[str, Any]], int | None]:
    root_comments: list[dict[str, Any]] = []
    reported_total: int | None = None
    page_number = 1

    while len(root_comments) < options.max_comments:
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
        page = data.get("page") if isinstance(data, dict) else {}
        if isinstance(page, dict) and isinstance(page.get("count"), int):
            reported_total = page["count"]

        replies = data.get("replies") if isinstance(data, dict) else None
        if not replies:
            break

        for reply in replies:
            if len(root_comments) >= options.max_comments:
                break
            root_item = normalize_comment(reply, video_info, "root")
            root_item["replyComments"] = fetch_replies_for_root(aid, reply, video_info, options, warnings)
            root_comments.append(root_item)

        if len(replies) < options.comment_page_size:
            break

        page_number += 1
        polite_sleep(options.delay)

    return root_comments, reported_total


def fetch_root_comments_by_cursor(
    aid: int,
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
) -> tuple[list[dict[str, Any]], int | None]:
    root_comments: list[dict[str, Any]] = []
    reported_total: int | None = None
    next_offset = ""
    signer = get_wbi_signer(options.timeout)

    while len(root_comments) < options.max_comments:
        payload = fetch_json(
            COMMENT_WBI_MAIN_API,
            signer.sign({
                "type": COMMENT_TYPE_VIDEO,
                "oid": aid,
                "mode": to_cursor_comment_mode(options.comment_sort),
                "ps": options.comment_page_size,
                "plat": 1,
                "web_location": 1315875,
                "pagination_str": json.dumps({"offset": next_offset}, separators=(",", ":")),
            }),
            options.timeout,
        )
        data = ensure_bili_success(payload, "新版评论列表接口")
        cursor = data.get("cursor") if isinstance(data, dict) else {}
        if isinstance(cursor, dict) and isinstance(cursor.get("all_count"), int):
            reported_total = cursor["all_count"]

        replies = data.get("replies") if isinstance(data, dict) else None
        if not replies:
            break

        for reply in replies:
            if len(root_comments) >= options.max_comments:
                break
            root_item = normalize_comment(reply, video_info, "root")
            root_item["replyComments"] = fetch_replies_for_root(aid, reply, video_info, options, warnings)
            root_comments.append(root_item)

        if not isinstance(cursor, dict) or cursor.get("is_end"):
            break
        next_value = extract_next_comment_offset(data)
        if not next_value or next_value == next_offset:
            break
        next_offset = next_value
        polite_sleep(options.delay)

    return root_comments, reported_total


def to_cursor_comment_mode(comment_sort: int) -> int:
    if comment_sort == 0:
        return 2
    return 3


def fetch_replies_for_root(
    aid: int,
    root_reply: dict[str, Any],
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
) -> list[dict[str, Any]]:
    if options.max_replies_per_comment == 0:
        return []

    root_rpid = root_reply.get("rpid")
    if not isinstance(root_rpid, int):
        return []

    reply_comments: list[dict[str, Any]] = []
    page_number = 1

    try:
        while len(reply_comments) < options.max_replies_per_comment:
            payload = fetch_json(
                COMMENT_REPLY_API,
                {
                    "type": COMMENT_TYPE_VIDEO,
                    "oid": aid,
                    "root": root_rpid,
                    "ps": options.reply_page_size,
                    "pn": page_number,
                },
                options.timeout,
            )
            data = ensure_bili_success(payload, "评论回复接口")
            replies = data.get("replies") if isinstance(data, dict) else None
            if not replies:
                break

            for reply in replies:
                if len(reply_comments) >= options.max_replies_per_comment:
                    break
                reply_comments.append(normalize_comment(reply, video_info, "reply"))

            if len(replies) < options.reply_page_size:
                break

            page_number += 1
            polite_sleep(options.delay)
    except BiliApiError as error:
        warnings.append(f"主楼评论 {root_rpid} 的旧回复接口读取失败，已尝试切换到新版回复接口：{error}")
        reply_comments = fetch_replies_for_root_by_wbi(aid, root_rpid, root_reply, video_info, options, warnings)

    return reply_comments


def fetch_replies_for_root_by_wbi(
    aid: int,
    root_rpid: int,
    root_reply: dict[str, Any],
    video_info: dict[str, Any],
    options: FetchOptions,
    warnings: list[str],
) -> list[dict[str, Any]]:
    reply_comments: list[dict[str, Any]] = []
    page_number = 1
    signer = get_wbi_signer(options.timeout)

    try:
        while len(reply_comments) < options.max_replies_per_comment:
            payload = fetch_json(
                COMMENT_WBI_REPLY_API,
                signer.sign({
                    "type": COMMENT_TYPE_VIDEO,
                    "oid": aid,
                    "root": root_rpid,
                    "ps": options.reply_page_size,
                    "pn": page_number,
                    "web_location": 1315875,
                }),
                options.timeout,
            )
            data = ensure_bili_success(payload, "新版评论回复接口")
            replies = data.get("replies") if isinstance(data, dict) else None
            if not replies:
                break

            for reply in replies:
                if len(reply_comments) >= options.max_replies_per_comment:
                    break
                reply_comments.append(normalize_comment(reply, video_info, "reply"))

            if len(replies) < options.reply_page_size:
                break
            page_number += 1
            polite_sleep(options.delay)
    except BiliApiError as error:
        warnings.append(f"主楼评论 {root_rpid} 的新版回复接口读取失败，已回退到评论列表内嵌回复：{error}")
        reply_comments = [
            normalize_comment(reply, video_info, "reply")
            for reply in root_reply.get("replies") or []
        ][: options.max_replies_per_comment]

    return reply_comments


def collect_danmaku_pages(
    pages: list[dict[str, Any]],
    options: FetchOptions,
    warnings: list[str],
) -> dict[str, Any]:
    page_items: list[dict[str, Any]] = []
    total_fetched = 0

    if options.max_danmaku == 0:
        return {"pageCount": 0, "totalFetched": 0, "pages": []}

    for page in pages:
        cid = page.get("cid")
        if not isinstance(cid, int):
            warnings.append(f"分 P {page.get('page')} 缺少 cid，已跳过弹幕读取")
            continue
        try:
            items = fetch_danmaku(cid, options)
        except (BiliApiError, ElementTree.ParseError) as error:
            warnings.append(f"分 P {page.get('page')} 弹幕读取失败：{error}")
            items = []

        page_items.append(
            {
                "page": page.get("page"),
                "cid": cid,
                "part": page.get("part"),
                "duration": page.get("duration"),
                "count": len(items),
                "items": items,
            }
        )
        total_fetched += len(items)
        polite_sleep(options.delay)

    return {
        "pageCount": len(page_items),
        "totalFetched": total_fetched,
        "pages": page_items,
    }


def fetch_danmaku(cid: int, options: FetchOptions) -> list[dict[str, Any]]:
    body = fetch_bytes(DANMAKU_XML_API, {"oid": cid}, options.timeout)
    root = ElementTree.fromstring(body)

    items: list[dict[str, Any]] = []
    for node in root.findall("d"):
        if len(items) >= options.max_danmaku:
            break
        items.append(normalize_danmaku(node))
    return items


def get_wbi_signer(timeout: int) -> WbiSigner:
    global _WBI_SIGNER_CACHE
    if _WBI_SIGNER_CACHE is not None:
        return _WBI_SIGNER_CACHE

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

    # WBI 签名 key 来自公开视频接口，缓存后可减少重复请求；这不是登录态，也不包含用户 Cookie。
    mixin_key = "".join(raw_key[index] for index in MIXIN_KEY_ENC_TAB)[:32]
    _WBI_SIGNER_CACHE = WbiSigner(mixin_key)
    return _WBI_SIGNER_CACHE


def extract_url_stem(value: Any) -> str:
    if not isinstance(value, str) or not value:
        raise BiliApiError("WBI 签名图片地址为空")
    return Path(urlparse(value).path).stem


def sanitize_wbi_value(value: Any) -> str:
    return "".join(char for char in str(value) if char not in "!'()*")


def extract_next_comment_offset(data: dict[str, Any]) -> str:
    cursor = data.get("cursor") if isinstance(data.get("cursor"), dict) else {}
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


def select_pages(video_info: dict[str, Any], options: FetchOptions) -> list[dict[str, Any]]:
    pages = video_info.get("pages")
    if not isinstance(pages, list) or not pages:
        cid = video_info.get("cid")
        if isinstance(cid, int):
            return [{"cid": cid, "page": 1, "part": video_info.get("title"), "duration": video_info.get("duration")}]
        raise ValueError("视频没有可用分 P 或 cid")

    if options.all_pages:
        return pages

    for page in pages:
        if page.get("page") == options.page:
            return [page]

    raise ValueError(f"视频不存在第 {options.page}P")


def normalize_video(video_info: dict[str, Any], selected_pages: list[dict[str, Any]]) -> dict[str, Any]:
    owner = video_info.get("owner") if isinstance(video_info.get("owner"), dict) else {}
    stat = video_info.get("stat") if isinstance(video_info.get("stat"), dict) else {}
    pages = video_info.get("pages") if isinstance(video_info.get("pages"), list) else []
    return {
        "bvid": video_info.get("bvid"),
        "aid": video_info.get("aid"),
        "title": video_info.get("title"),
        "description": video_info.get("desc"),
        "duration": video_info.get("duration"),
        "pubdate": to_iso_time(video_info.get("pubdate")),
        "owner": {
            "mid": owner.get("mid"),
            "name": owner.get("name"),
        },
        "stat": {
            "view": stat.get("view"),
            "danmaku": stat.get("danmaku"),
            "reply": stat.get("reply"),
            "like": stat.get("like"),
            "coin": stat.get("coin"),
            "favorite": stat.get("favorite"),
            "share": stat.get("share"),
        },
        "pages": [normalize_page(page) for page in pages],
        "selectedPages": [normalize_page(page) for page in selected_pages],
    }


def normalize_page(page: dict[str, Any]) -> dict[str, Any]:
    return {
        "page": page.get("page"),
        "cid": page.get("cid"),
        "part": page.get("part"),
        "duration": page.get("duration"),
    }


def normalize_comment(reply: dict[str, Any], video_info: dict[str, Any], comment_type: str) -> dict[str, Any]:
    member = reply.get("member") if isinstance(reply.get("member"), dict) else {}
    content = reply.get("content") if isinstance(reply.get("content"), dict) else {}
    owner = video_info.get("owner") if isinstance(video_info.get("owner"), dict) else {}
    mid = str(member.get("mid") or reply.get("mid") or "")
    owner_mid = str(owner.get("mid") or "")

    return {
        "commentType": comment_type,
        "rpid": reply.get("rpid"),
        "root": reply.get("root"),
        "parent": reply.get("parent"),
        "floor": reply.get("floor"),
        "ctime": reply.get("ctime"),
        "ctimeText": to_iso_time(reply.get("ctime")),
        "like": reply.get("like"),
        "replyCount": reply.get("rcount"),
        "message": content.get("message", ""),
        "member": {
            "mid": mid,
            "name": member.get("uname"),
            "isVideoOwner": bool(mid and owner_mid and mid == owner_mid),
            "level": (member.get("level_info") or {}).get("current_level")
            if isinstance(member.get("level_info"), dict)
            else None,
        },
    }


def normalize_danmaku(node: ElementTree.Element) -> dict[str, Any]:
    parts = (node.attrib.get("p") or "").split(",")
    progress_seconds = parse_float_at(parts, 0)
    send_timestamp = parse_int_at(parts, 4)
    return {
        "progressSeconds": progress_seconds,
        "progressText": seconds_to_timestamp(progress_seconds),
        "mode": parse_int_at(parts, 1),
        "fontSize": parse_int_at(parts, 2),
        "color": parse_int_at(parts, 3),
        "sendTime": send_timestamp,
        "sendTimeText": to_iso_time(send_timestamp),
        "pool": parse_int_at(parts, 5),
        "userHash": parts[6] if len(parts) > 6 else "",
        "danmakuId": parts[7] if len(parts) > 7 else "",
        "text": node.text or "",
    }


def parse_int_at(parts: list[str], index: int) -> int | None:
    if index >= len(parts) or parts[index] == "":
        return None
    try:
        return int(float(parts[index]))
    except ValueError:
        return None


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
    millis = int(round((seconds - total_seconds) * 1000))
    minutes, second = divmod(total_seconds, 60)
    hour, minute = divmod(minutes, 60)
    if hour:
        return f"{hour:02d}:{minute:02d}:{second:02d}.{millis:03d}"
    return f"{minute:02d}:{second:02d}.{millis:03d}"


def to_iso_time(timestamp: Any) -> str | None:
    if not isinstance(timestamp, int) or timestamp <= 0:
        return None
    return datetime.fromtimestamp(timestamp, timezone.utc).isoformat(timespec="seconds")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def empty_comments(reason: str) -> dict[str, Any]:
    return {
        "oid": None,
        "type": COMMENT_TYPE_VIDEO,
        "reportedTotal": None,
        "rootCount": 0,
        "replyCount": 0,
        "emptyReason": reason,
        "rootComments": [],
    }


def polite_sleep(delay: float) -> None:
    if delay > 0:
        time.sleep(delay)


def write_outputs(payload: dict[str, Any], options: FetchOptions) -> list[Path]:
    options.output_dir.mkdir(parents=True, exist_ok=True)
    base_name = f"{options.bvid}_feedback"
    output_paths: list[Path] = []

    if options.output_format in ("json", "both"):
        json_path = options.output_dir / f"{base_name}.json"
        json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        output_paths.append(json_path)

    if options.output_format in ("txt", "both"):
        txt_path = options.output_dir / f"{base_name}.txt"
        txt_path.write_text(render_text(payload), encoding="utf-8")
        output_paths.append(txt_path)

    return output_paths


def render_text(payload: dict[str, Any]) -> str:
    video = payload["video"]
    comments = payload["comments"]
    danmaku = payload["danmaku"]
    lines: list[str] = [
        "# B 站评论弹幕样例",
        "",
        "## 视频信息",
        f"BV：{video.get('bvid')}",
        f"标题：{video.get('title')}",
        f"UP 主：{(video.get('owner') or {}).get('name')}",
        "",
        "## 评论样例",
        f"主楼评论数：{comments.get('rootCount')}，回复评论数：{comments.get('replyCount')}",
        "",
    ]

    for index, root in enumerate(comments.get("rootComments") or [], start=1):
        lines.extend(
            [
                f"### 主楼评论 {index}",
                format_comment_line(root),
                root.get("message") or "",
                "",
            ]
        )
        reply_comments = root.get("replyComments") or []
        if reply_comments:
            lines.append("#### 回复评论")
            for reply_index, reply in enumerate(reply_comments, start=1):
                lines.append(f"{reply_index}. {format_comment_line(reply)} {reply.get('message') or ''}")
            lines.append("")

    lines.extend(["## 弹幕样例", ""])
    for page in danmaku.get("pages") or []:
        lines.append(f"### P{page.get('page')} {page.get('part')}")
        for item in page.get("items") or []:
            lines.append(f"[{item.get('progressText')}] {item.get('text')}")
        lines.append("")

    warnings = payload.get("warnings") or []
    if warnings:
        lines.extend(["## 采集提示", ""])
        for warning in warnings:
            lines.append(f"- {warning}")

    return "\n".join(lines).strip() + "\n"


def format_comment_line(comment: dict[str, Any]) -> str:
    member = comment.get("member") if isinstance(comment.get("member"), dict) else {}
    owner_mark = "（视频作者）" if member.get("isVideoOwner") else ""
    time_text = comment.get("ctimeText") or ""
    return f"{member.get('name') or '未知用户'}{owner_mark} · {time_text} · {comment.get('like') or 0} 赞："


if __name__ == "__main__":
    raise SystemExit(main())
