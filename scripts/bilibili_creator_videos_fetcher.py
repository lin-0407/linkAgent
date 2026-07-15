#!/usr/bin/env python3
"""
B站创作者公开视频同步脚本。

脚本只读取公开接口，不使用 Cookie，也不绕过登录或风控。它负责拉取账号最近公开视频，
并对后端传入的任务 BV 做定向详情查询，输出单个 JSON 给 Spring Boot 同步服务。
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
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen


SPACE_ARCHIVE_API = "https://api.bilibili.com/x/space/wbi/arc/search"
SPACE_INFO_API = "https://api.bilibili.com/x/space/wbi/acc/info"
VIDEO_INFO_API = "https://api.bilibili.com/x/web-interface/view"
WBI_NAV_API = "https://api.bilibili.com/x/web-interface/nav"

DEFAULT_TIMEOUT_SECONDS = 15
PAGE_SIZE = 30
BVID_PATTERN = re.compile(r"^BV[0-9A-Za-z]{10}$")
UID_PATTERN = re.compile(r"^[0-9]+$")

# WBI 签名使用公开算法的固定置换表，不包含账号凭据。
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
    def __init__(self, message: str, code: int | None = None):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class WbiSigner:
    mixin_key: str

    def sign(self, params: dict[str, Any]) -> dict[str, Any]:
        signed_params = dict(params)
        signed_params["wts"] = int(time.time())
        encoded_query = urlencode(
            sorted((key, sanitize_wbi_value(value)) for key, value in signed_params.items())
        )
        signed_params["w_rid"] = hashlib.md5(
            f"{encoded_query}{self.mixin_key}".encode("utf-8")
        ).hexdigest()
        return signed_params


def main() -> int:
    args = parse_args()
    try:
        payload = collect_payload(args)
    except (BiliApiError, ValueError, OSError, json.JSONDecodeError) as error:
        print(f"同步失败：{error}", file=sys.stderr)
        return 1

    json.dump(payload, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="同步B站账号的公开作品并校验任务BV归属")
    parser.add_argument("--uid", required=True, help="B站UID")
    parser.add_argument("--max-videos", type=positive_int, default=100, help="最近公开视频数量上限")
    parser.add_argument(
        "--target-bvid",
        action="append",
        default=[],
        help="需要定向校验的BV号，可重复传入",
    )
    parser.add_argument("--timeout", type=positive_int, default=DEFAULT_TIMEOUT_SECONDS)
    args = parser.parse_args()
    if not UID_PATTERN.fullmatch(args.uid):
        parser.error("UID只能包含数字")
    invalid_bvids = [value for value in args.target_bvid if not BVID_PATTERN.fullmatch(value)]
    if invalid_bvids:
        parser.error(f"BV号格式不正确：{invalid_bvids[0]}")
    return args


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("必须是大于0的整数")
    return parsed


def collect_payload(args: argparse.Namespace) -> dict[str, Any]:
    warnings: list[str] = []
    signer: WbiSigner | None = None
    try:
        signer = get_wbi_signer(args.timeout)
    except BiliApiError as error:
        # WBI接口受限时仍继续用公开视频详情接口校验任务BV，保住手动绑定主链路。
        warnings.append(f"WBI签名信息读取失败：{error}")

    nickname = fetch_nickname(args.uid, signer, args.timeout, warnings) if signer else None

    videos_by_bvid: dict[str, dict[str, Any]] = {}
    archive_succeeded = False
    has_more = False
    if signer:
        try:
            archive_videos, has_more = fetch_recent_videos(
                args.uid,
                args.max_videos,
                signer,
                args.timeout,
            )
            archive_succeeded = True
            for video in archive_videos:
                videos_by_bvid[video["bvid"]] = video
                if not nickname and video.get("authorName"):
                    nickname = video["authorName"]
        except BiliApiError as error:
            warnings.append(f"账号公开视频列表读取失败：{error}")

    verification_results: list[dict[str, Any]] = []
    for index, bvid in enumerate(dict.fromkeys(args.target_bvid)):
        result, video = verify_target_video(bvid, args.uid, args.timeout)
        verification_results.append(result)
        if video is not None:
            videos_by_bvid[bvid] = video
        if result["status"] == "UNKNOWN":
            warnings.append(result["message"])
        if index < len(args.target_bvid) - 1:
            time.sleep(0.2)

    has_decisive_verification = any(
        result["status"] != "UNKNOWN" for result in verification_results
    )
    if not archive_succeeded and not has_decisive_verification:
        raise BiliApiError("公开视频列表和任务BV校验均未成功，未写入任何同步数据")

    return {
        "bilibiliUid": args.uid,
        "nickname": nickname,
        "hasMore": has_more,
        "partial": bool(warnings),
        "videos": list(videos_by_bvid.values()),
        "verificationResults": verification_results,
        "warnings": warnings,
    }


def fetch_nickname(
    uid: str,
    signer: WbiSigner,
    timeout: int,
    warnings: list[str],
) -> str | None:
    params = signer.sign({"mid": uid, "platform": "web", "web_location": 1550101})
    try:
        data = ensure_bili_success(fetch_json(SPACE_INFO_API, params, timeout), "账号信息接口")
    except BiliApiError as error:
        warnings.append(f"账号昵称读取失败：{error}")
        return None
    if not isinstance(data, dict):
        return None
    returned_uid = normalize_identifier(data.get("mid"))
    if returned_uid and returned_uid != uid:
        raise BiliApiError("账号信息接口返回的UID与请求不一致")
    name = data.get("name")
    return name.strip() if isinstance(name, str) and name.strip() else None


def fetch_recent_videos(
    uid: str,
    max_videos: int,
    signer: WbiSigner,
    timeout: int,
) -> tuple[list[dict[str, Any]], bool]:
    videos: list[dict[str, Any]] = []
    total_count = 0
    page_number = 1

    while len(videos) < max_videos:
        params = signer.sign({
            "mid": uid,
            "pn": page_number,
            "ps": min(PAGE_SIZE, max_videos - len(videos)),
            "tid": 0,
            "keyword": "",
            "order": "pubdate",
            "platform": "web",
            "web_location": 1550101,
            "order_avoided": "true",
        })
        data = ensure_bili_success(fetch_json(SPACE_ARCHIVE_API, params, timeout), "公开视频列表接口")
        if not isinstance(data, dict):
            raise BiliApiError("公开视频列表接口没有返回数据")

        page = data.get("page") if isinstance(data.get("page"), dict) else {}
        total_count = parse_non_negative_int(page.get("count")) or total_count
        listing = data.get("list") if isinstance(data.get("list"), dict) else {}
        entries = listing.get("vlist") if isinstance(listing.get("vlist"), list) else []
        if not entries:
            break

        for entry in entries:
            if not isinstance(entry, dict):
                continue
            normalized = normalize_archive_video(entry, uid)
            if normalized is not None:
                videos.append(normalized)
            if len(videos) >= max_videos:
                break

        if len(entries) < params["ps"] or (total_count and len(videos) >= total_count):
            break
        page_number += 1
        time.sleep(0.4)

    return videos, total_count > len(videos)


def verify_target_video(
    bvid: str,
    expected_uid: str,
    timeout: int,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    try:
        data = ensure_bili_success(
            fetch_json(VIDEO_INFO_API, {"bvid": bvid}, timeout),
            "视频详情接口",
        )
    except BiliApiError as error:
        if error.code in (-404, 62002, 62004, 1001002):
            return verification(bvid, "VIDEO_NOT_FOUND", None, "B站未找到可公开访问的该视频"), None
        return verification(bvid, "UNKNOWN", None, f"视频 {bvid} 暂时无法校验：{error}"), None

    if not isinstance(data, dict):
        return verification(bvid, "UNKNOWN", None, f"视频 {bvid} 详情为空，暂时无法校验"), None
    owner = data.get("owner") if isinstance(data.get("owner"), dict) else {}
    owner_uid = normalize_identifier(owner.get("mid"))
    if not owner_uid:
        return verification(bvid, "UNKNOWN", None, f"视频 {bvid} 没有返回明确的所属UID"), None
    video = normalize_detail_video(bvid, data, owner_uid)
    if owner_uid != expected_uid:
        message = f"视频属于UID {owner_uid or '未知'}，与当前绑定UID {expected_uid} 不一致"
        return verification(bvid, "UID_MISMATCH", owner_uid, message), video
    return verification(bvid, "FOUND", owner_uid, "BV归属校验通过"), video


def verification(bvid: str, status: str, owner_uid: str | None, message: str) -> dict[str, Any]:
    return {
        "bvid": bvid,
        "status": status,
        "ownerUid": owner_uid,
        "message": message,
    }


def normalize_archive_video(entry: dict[str, Any], uid: str) -> dict[str, Any] | None:
    bvid = entry.get("bvid")
    if not isinstance(bvid, str) or not BVID_PATTERN.fullmatch(bvid):
        return None
    return {
        "bvid": bvid,
        "aid": parse_positive_int(entry.get("aid")),
        "title": normalize_text(entry.get("title")),
        "coverUrl": normalize_cover_url(entry.get("pic")),
        "publishTimestamp": parse_positive_int(entry.get("created")),
        "viewCount": parse_non_negative_int(entry.get("play")),
        "likeCount": None,
        "coinCount": None,
        "favoriteCount": None,
        "shareCount": None,
        "ownerUid": normalize_identifier(entry.get("mid")) or uid,
        "authorName": normalize_text(entry.get("author")),
        "rawSnapshot": compact_json(entry),
    }


def normalize_detail_video(
    bvid: str,
    data: dict[str, Any],
    owner_uid: str | None,
) -> dict[str, Any]:
    stat = data.get("stat") if isinstance(data.get("stat"), dict) else {}
    return {
        "bvid": bvid,
        "aid": parse_positive_int(data.get("aid")),
        "title": normalize_text(data.get("title")),
        "coverUrl": normalize_cover_url(data.get("pic")),
        "publishTimestamp": parse_positive_int(data.get("pubdate")),
        "viewCount": parse_non_negative_int(stat.get("view")),
        "likeCount": parse_non_negative_int(stat.get("like")),
        "coinCount": parse_non_negative_int(stat.get("coin")),
        "favoriteCount": parse_non_negative_int(stat.get("favorite")),
        "shareCount": parse_non_negative_int(stat.get("share")),
        "ownerUid": owner_uid,
        "rawSnapshot": compact_json(data),
    }


def get_wbi_signer(timeout: int) -> WbiSigner:
    response = fetch_json(WBI_NAV_API, {}, timeout)
    data = response.get("data")
    if not isinstance(data, dict):
        data = ensure_bili_success(response, "WBI签名信息接口")
    wbi_img = data.get("wbi_img") if isinstance(data, dict) else None
    if not isinstance(wbi_img, dict):
        raise BiliApiError("WBI签名信息接口没有返回wbi_img")

    img_key = extract_url_stem(wbi_img.get("img_url"))
    sub_key = extract_url_stem(wbi_img.get("sub_url"))
    raw_key = f"{img_key}{sub_key}"
    if len(raw_key) < max(MIXIN_KEY_ENC_TAB) + 1:
        raise BiliApiError("WBI签名key长度异常")
    mixin_key = "".join(raw_key[index] for index in MIXIN_KEY_ENC_TAB)[:32]
    return WbiSigner(mixin_key)


def extract_url_stem(value: Any) -> str:
    if not isinstance(value, str) or not value:
        raise BiliApiError("WBI签名图片地址为空")
    return Path(urlparse(value).path).stem


def sanitize_wbi_value(value: Any) -> str:
    return "".join(char for char in str(value) if char not in "!'()*")


def fetch_json(url: str, params: dict[str, Any], timeout: int) -> dict[str, Any]:
    full_url = f"{url}?{urlencode(params)}"
    request = Request(
        full_url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
            ),
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://space.bilibili.com/",
        },
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
            encoding = response.headers.get("Content-Encoding", "").lower()
    except HTTPError as error:
        raise BiliApiError(f"HTTP {error.code}") from error
    except TimeoutError as error:
        raise BiliApiError("B站接口请求超时") from error
    except URLError as error:
        raise BiliApiError(f"网络请求失败：{error.reason}") from error
    try:
        decoded = decode_response_body(body, encoding)
        result = json.loads(decoded.decode("utf-8"))
    except (UnicodeDecodeError, OSError, json.JSONDecodeError) as error:
        raise BiliApiError("B站接口返回内容无法解析") from error
    if not isinstance(result, dict):
        raise BiliApiError("B站接口没有返回JSON对象")
    return result


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
        raise BiliApiError(f"{api_name}被限制访问：{message}。脚本不会使用Cookie或绕过风控", code)
    raise BiliApiError(f"{api_name}返回错误 code={code}, message={message}", code)


def normalize_identifier(value: Any) -> str | None:
    if isinstance(value, int) and value >= 0:
        return str(value)
    if isinstance(value, str) and value.isdigit():
        return value
    return None


def parse_positive_int(value: Any) -> int | None:
    parsed = parse_non_negative_int(value)
    return parsed if parsed is not None and parsed > 0 else None


def parse_non_negative_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value >= 0:
        return value
    if isinstance(value, str) and value.isdigit():
        return int(value)
    return None


def normalize_text(value: Any) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def normalize_cover_url(value: Any) -> str | None:
    normalized = normalize_text(value)
    if normalized and normalized.startswith("//"):
        return f"https:{normalized}"
    return normalized


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    raise SystemExit(main())
