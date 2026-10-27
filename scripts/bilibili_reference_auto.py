#!/usr/bin/env python3
"""自动发现近期参考视频；SQLite 保存候选、重查时间和待入库正文，单次运行可交给系统计划任务。"""
from __future__ import annotations

import argparse
import calendar
import contextlib
import json
import math
import os
import re
import sqlite3
import sys
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

try:
    from . import bilibili_reference_fetcher as fetcher
    from .bilibili_video_index import score_video, FORMULA_VERSION
except ImportError:
    import bilibili_reference_fetcher as fetcher
    from bilibili_video_index import score_video, FORMULA_VERSION

CHINA = timezone(timedelta(hours=8))
POPULAR = "https://api.bilibili.com/x/web-interface/popular"
NEWLIST = "https://api.bilibili.com/x/web-interface/newlist"
BVID = re.compile(r"BV[0-9A-Za-z]{10}\Z")
DEFAULTS = dict(rids=[1, 3, 4, 36, 188, 160, 211, 181], popular_pages=6,
                newlist_pages=2, rankings=False, page_size=50, min_index=60.0,
                max_checks=100, max_imports=10, max_comments=100, max_danmaku=100,
                delay_seconds=1.5, timeout_seconds=20, import_timeout_seconds=180,
                recheck_hours=24, retry_minutes=30,
                api_base="http://127.0.0.1:8081", maintenance=True,
                rebuild_indexes=False, state_path="export/bilibili_reference/auto-state.sqlite3")


class AccessBlocked(RuntimeError):
    pass


def months_before(now: datetime, months: int) -> datetime:
    value = now.year * 12 + now.month - 1 - months
    year, month0 = divmod(value, 12)
    month = month0 + 1
    return now.replace(year=year, month=month,
                       day=min(now.day, calendar.monthrange(year, month)[1]))


def recent(pubdate, now: datetime) -> bool:
    if isinstance(pubdate, bool) or not isinstance(pubdate, int) or pubdate <= 0:
        return False
    return months_before(now, 6).timestamp() <= pubdate <= now.timestamp()


def load_config(path: Path | None) -> dict:
    config = dict(DEFAULTS)
    if path:
        provided = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(provided, dict) or set(provided) - set(DEFAULTS):
            raise ValueError("配置必须是 JSON 对象，且不能包含未知配置项")
        config.update(provided)
    for key in ("popular_pages", "newlist_pages", "max_comments", "max_danmaku"):
        if type(config[key]) is not int or not 0 <= config[key] <= 1000:
            raise ValueError(f"{key} 必须是 0–1000 的整数")
    for key, maximum in (("page_size", 50), ("max_checks", 10000)):
        if type(config[key]) is not int or not 1 <= config[key] <= maximum:
            raise ValueError(f"{key} 必须是 1–{maximum} 的整数")
    # 单轮入库上限不再卡 1000，只拦非正整数；实际能入库多少仍受候选数、max_checks 和后端逐条导入限制。
    if type(config["max_imports"]) is not int or config["max_imports"] < 1:
        raise ValueError("max_imports 必须是正整数")
    for key in ("delay_seconds", "timeout_seconds", "import_timeout_seconds", "recheck_hours", "retry_minutes"):
        value = config[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise ValueError(f"{key} 必须是有限正数")
    threshold = config["min_index"]
    if isinstance(threshold, bool) or not isinstance(threshold, (int, float)) or not math.isfinite(threshold) or not 0 <= threshold <= 100:
        raise ValueError("min_index 必须是 0–100 的有限数值")
    if not isinstance(config["rids"], list) or any(type(rid) is not int or rid <= 0 for rid in config["rids"]):
        raise ValueError("rids 必须是正整数数组；空数组代表仅采热门")
    config["rids"] = list(dict.fromkeys(config["rids"]))
    for key in ("rankings", "maintenance", "rebuild_indexes"):
        if type(config[key]) is not bool:
            raise ValueError(f"{key} 必须是布尔值")
    if not isinstance(config["state_path"], str) or not config["state_path"].strip():
        raise ValueError("state_path 不能为空")
    if not isinstance(config["api_base"], str):
        raise ValueError("api_base 必须是 HTTP(S) 地址")
    address = urlparse(config["api_base"])
    if address.scheme not in ("http", "https") or not address.hostname or address.username or address.password or address.query or address.fragment:
        raise ValueError("api_base 必须是无账号、查询参数和片段的 HTTP(S) 后端地址")
    config["api_base"] = config["api_base"].rstrip("/")
    return config


@contextlib.contextmanager
def state_lock(path: Path):
    # 系统文件锁会在进程退出时自动释放，避免崩溃留下永久的锁文件。
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_suffix(path.suffix + ".lock").open("a+b") as handle:
        handle.seek(0)
        if not handle.read(1):
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError("相同状态文件已有采集进程运行") from error
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def open_state(path: Path) -> sqlite3.Connection:
    database = sqlite3.connect(path)
    database.row_factory = sqlite3.Row
    database.executescript("""
        CREATE TABLE IF NOT EXISTS candidates (
            bvid TEXT PRIMARY KEY, discovered REAL NOT NULL, last_seen REAL NOT NULL,
            published INTEGER, checked REAL, next_check REAL NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'NEW', score REAL, error TEXT,
            pending_payload TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_due ON candidates(next_check, checked);
        CREATE TABLE IF NOT EXISTS source_progress (
            source TEXT PRIMARY KEY, page INTEGER NOT NULL, seen TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    return database


class PublicRequests:
    def __init__(self, config):
        self.config = config
        self.original = fetcher.fetch_bytes
        self.count = 0
        self.last_request = 0.0

    def __call__(self, url, params, timeout):
        remaining = self.config["delay_seconds"] - (time.monotonic() - self.last_request)
        if remaining > 0:
            time.sleep(remaining)
        self.last_request = time.monotonic()
        self.count += 1
        try:
            body = self.original(url, params, timeout)
        except fetcher.BiliApiError as error:
            if re.search(r"HTTP (403|412|429)\b", str(error)):
                raise AccessBlocked("公开接口要求验证或限流，本轮停止；不使用 Cookie 绕过") from error
            raise
        if body.lstrip().startswith(b"{"):
            value = json.loads(body)
            if value.get("code") in (-101, -352, -403, -412, 429) or isinstance(value.get("data"), dict) and value["data"].get("v_voucher"):
                raise AccessBlocked("公开接口要求验证或限流，本轮停止；不使用 Cookie 绕过")
        return body


def source_specs(config):
    if config["popular_pages"]:
        yield "popular", POPULAR, {}, "list", config["popular_pages"]
    for rid in config["rids"]:
        if config["rankings"]:
            yield f"ranking:{rid}", fetcher.RANKING_API, {"rid": rid, "type": "all"}, "list", 1
        if config["newlist_pages"]:
            yield f"newlist:{rid}", NEWLIST, {"rid": rid}, "archives", config["newlist_pages"]


def discover(database, config, report):
    for name, url, params, field, max_pages in source_specs(config):
        progress = database.execute("SELECT * FROM source_progress WHERE source=?", (name,)).fetchone()
        page = progress["page"] if progress else 1
        seen = set(json.loads(progress["seen"])) if progress else set()
        try:
            while page <= max_pages:
                data = fetcher.ensure_bili_success(fetcher.fetch_json(url, dict(params, pn=page, ps=config["page_size"]), config["timeout_seconds"]), name)
                if not isinstance(data, dict) or not isinstance(data.get(field), list):
                    raise ValueError(f"{name} 返回结构不完整，不能视为空列表")
                entries = data[field]
                ids = {item.get("bvid") for item in entries if isinstance(item, dict) and isinstance(item.get("bvid"), str) and BVID.fullmatch(item["bvid"])}
                if entries and not ids:
                    raise ValueError(f"{name} 没有可识别的 BV，不能视为采集完成")
                repeated = bool(ids and seen and len(ids & seen) / len(ids) >= 0.8)
                now = datetime.now(CHINA)
                with database:
                    for item in entries:
                        if not isinstance(item, dict) or item.get("bvid") not in ids:
                            continue
                        # 列表的时间只用于提前剔除；真正入库前仍必须查详情核验。
                        pubdate = item.get("pubdate")
                        if type(pubdate) is int and not recent(pubdate, now):
                            continue
                        existing = database.execute("SELECT 1 FROM candidates WHERE bvid=?", (item["bvid"],)).fetchone()
                        database.execute("""INSERT INTO candidates(bvid, discovered, last_seen, published, next_check)
                            VALUES(?,?,?,?,?) ON CONFLICT(bvid) DO UPDATE SET last_seen=excluded.last_seen""",
                            (item["bvid"], now.timestamp(), now.timestamp(), pubdate if type(pubdate) is int else None, now.timestamp()))
                        report["discovered"] += int(existing is None)
                    seen.update(ids)
                    finished = not entries or repeated or page == max_pages
                    database.execute("INSERT OR REPLACE INTO source_progress VALUES(?,?,?)", (name, 1 if finished else page + 1, "[]" if finished else json.dumps(sorted(seen))))
                report["pages"] += 1
                if repeated:
                    report["warnings"].append(f"{name} 第{page}页重复率≥80%，停止该来源分页，未证明半年回溯")
                if finished:
                    break
                page += 1
        except AccessBlocked:
            raise
        except (fetcher.BiliApiError, ValueError, OSError) as error:
            # 来源失败不推进游标；其它独立来源仍可补充候选。
            report["sourceFailures"] += 1
            report["warnings"].append(f"{name} 失败，保留断点：{str(error)[:240]}")


def backend_post(config, route, payload, timeout=None):
    request = Request(config["api_base"] + "/api/knowledge/reference-videos" + route,
                      data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                      headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urlopen(request, timeout=timeout or config["timeout_seconds"]) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        # 不输出完整服务响应，避免日志混入模型提示或部署信息。
        raise RuntimeError(f"后端 {route} 返回 HTTP {error.code}") from error
    except (URLError, TimeoutError, json.JSONDecodeError) as error:
        raise RuntimeError(f"后端 {route} 不可用或响应无效") from error


def save_check(database, bvid, status, config, *, published=None, score=None, payload=None, error=None):
    now = datetime.now(CHINA).timestamp()
    # 预览通过的候选仍可立刻正式采集，不让一次dry-run把入库推迟一天。
    wait = 0 if status == "ELIGIBLE" else config["retry_minutes"] * 60 if status == "RETRY" else config["recheck_hours"] * 3600
    with database:
        database.execute("""UPDATE candidates SET status=?, checked=?, next_check=?,
            published=COALESCE(?,published), score=?, pending_payload=?, error=? WHERE bvid=?""",
            (status, now, now + wait, published, score, payload, error, bvid))


def check_candidates(database, config, report, dry_run=False):
    now = datetime.now(CHINA)
    # 保存的正文尚未确认入库时优先重试；API 成功才清除。低分候选按时间重新读取统计。
    rows = database.execute("SELECT * FROM candidates WHERE next_check<=? ORDER BY (pending_payload IS NULL), next_check, discovered LIMIT ?",
                            (now.timestamp(), config["max_checks"])).fetchall()
    existing = set()
    if rows and not dry_run:
        for start in range(0, len(rows), 200):
            result = backend_post(config, "/collection/status", {"bvIds": [row["bvid"] for row in rows[start:start + 200]]})
            if not isinstance(result, dict) or not isinstance(result.get("existingBvIds"), list):
                raise RuntimeError("查重响应不完整，本轮不采正文")
            existing.update(result["existingBvIds"])
    options = fetcher.FetchOptions("bilibili_auto", "BENCHMARK", None, config["max_comments"], config["max_danmaku"], 20, 1, 0, config["timeout_seconds"])
    signer_state = {}
    for row in rows:
        if report["imported"] + report["updated"] + report["skipped"] >= config["max_imports"]:
            break
        bvid = row["bvid"]
        pending = row["pending_payload"]
        if pending:
            saved = json.loads(pending)
            if saved["videos"][0]["statsFetchedAtEpochSeconds"] < now.timestamp() - 24 * 3600:
                # 超过一天的待导入统计重新读取，不让离线期间积压的旧快照成为新准入依据。
                pending = None
        try:
            if pending and not dry_run:
                payload = json.loads(pending)
                # 重试也用本轮阈值；发布满半年后不再新增采集，不靠旧判定绕过时间窗口。
                video = payload["videos"][0]
                score = score_video(video["stats"])
                if not recent(video["publishedAtEpochSeconds"], datetime.now(CHINA)) or score.value is None or score.value <= config["min_index"]:
                    save_check(database, bvid, "INELIGIBLE", config, published=video["publishedAtEpochSeconds"], score=score.value)
                    continue
                payload["minVideoIndex"] = config["min_index"]
            else:
                info = fetcher.fetch_video_info(bvid, config["timeout_seconds"])
                fetched_at = int(datetime.now(CHINA).timestamp())
                published = info.get("pubdate")
                report["checked"] += 1
                if info.get("bvid") != bvid or not recent(published, datetime.now(CHINA)):
                    save_check(database, bvid, "INELIGIBLE", config, published=published if type(published) is int else None)
                    report["outsideWindow"] += 1
                    continue
                score = score_video(info["stat"] if isinstance(info.get("stat"), dict) else {})
                if score.value is None or score.value <= config["min_index"]:
                    save_check(database, bvid, "LOW_INDEX", config, published=published, score=score.value, error=score.error)
                    report["belowIndex"] += 1
                    continue
                report["eligible"] += 1
                if dry_run:
                    save_check(database, bvid, "ELIGIBLE", config, published=published, score=score.value)
                    continue
                # 已入库视频只刷新公开统计；不会重复抓正文、摘要或改写原来的案例层级。
                video_options = options if bvid not in existing else fetcher.FetchOptions("bilibili_auto", "BENCHMARK", None, 0, 0, 20, 1, 0, config["timeout_seconds"])
                video = fetcher.build_video_item(bvid, video_options, report["warnings"], signer_state, video_info=info)
                video["publishedAtEpochSeconds"] = published
                video["statsFetchedAtEpochSeconds"] = fetched_at
                payload = {"source": "bilibili_auto", "tier": "BENCHMARK", "minVideoIndex": config["min_index"], "videos": [video]}
                pending = json.dumps(payload, ensure_ascii=False)
                with database:
                    database.execute("UPDATE candidates SET pending_payload=?, published=?, score=? WHERE bvid=?", (pending, published, score.value, bvid))
            result = backend_post(config, "/import", payload, config["import_timeout_seconds"])
            counts = [result.get(key, 0) for key in ("importedCount", "updatedCount", "skippedCount")]
            if any(type(value) is not int or value < 0 for value in counts) or sum(counts) != 1:
                raise RuntimeError("入库响应计数不完整，保留正文以便重试")
            for key, count in zip(("imported", "updated", "skipped"), counts):
                report[key] += count
            save_check(database, bvid, "IMPORTED", config, published=payload["videos"][0]["publishedAtEpochSeconds"], score=score.value)
        except AccessBlocked:
            save_check(database, bvid, "RETRY", config, payload=pending, error="公开接口要求验证或限流")
            raise
        except (fetcher.BiliApiError, RuntimeError, ValueError, OSError) as error:
            save_check(database, bvid, "RETRY", config, payload=pending, error=str(error)[:300])
            report["failed"] += 1
            report["warnings"].append(f"{bvid} 失败，保留待重试记录：{str(error)[:200]}")


def run(config, *, discover_only=False, dry_run=False, skip_discovery=False):
    report = dict(startedAt=datetime.now(CHINA).isoformat(), formulaVersion=FORMULA_VERSION,
                  minIndex=config["min_index"], pages=0, discovered=0, checked=0, eligible=0,
                  belowIndex=0, outsideWindow=0, imported=0, updated=0, skipped=0, failed=0, sourceFailures=0, warnings=[])
    state_path = Path(config["state_path"]).resolve()
    limiter = PublicRequests(config)
    with state_lock(state_path), contextlib.closing(open_state(state_path)) as database:
        selection = json.dumps([FORMULA_VERSION, config["min_index"], config["api_base"]])
        previous = database.execute("SELECT value FROM settings WHERE key='selection'").fetchone()
        if previous and previous[0] != selection:
            # 改阈值、公式或后端时重新核对候选，不沿用旧库的已入库判断。
            with database:
                database.execute("UPDATE candidates SET next_check=0")
        with database:
            database.execute("INSERT OR REPLACE INTO settings VALUES('selection',?)", (selection,))
        original = fetcher.fetch_bytes
        fetcher.fetch_bytes = limiter
        try:
            if not discover_only and not dry_run and config["maintenance"]:
                # 旧库补结构化时间与指数，分页回填游标由后端返回；未知日期不猜测。
                cursor = 0
                while True:
                    result = backend_post(config, "/maintenance/backfill", {"afterId": cursor, "limit": 200})
                    if not result.get("hasMore"):
                        break
                    next_cursor = result.get("nextId")
                    if type(next_cursor) is not int or next_cursor <= cursor:
                        raise RuntimeError("历史回填游标没有推进")
                    cursor = next_cursor
                report["maintenance"] = backend_post(config, "/maintenance/purge", {"batchSize": 100}, config["import_timeout_seconds"])
            if not skip_discovery:
                discover(database, config, report)
            if not discover_only:
                check_candidates(database, config, report, dry_run)
                if not dry_run and config["rebuild_indexes"] and report["imported"]:
                    # 索引调用显式开启才付出 embedding 成本；失败不撤销已经提交的 MySQL 正文。
                    for route in ("/index/rebuild", "/index/chunks/rebuild", "/index/items/rebuild"):
                        try:
                            report[route] = backend_post(config, route, {"maxItems": min(config["max_imports"] * 3, 1000)}, config["import_timeout_seconds"])
                        except RuntimeError as error:
                            report["warnings"].append(str(error))
            boundary = int(months_before(datetime.now(CHINA), 6).timestamp())
            with database:
                database.execute("DELETE FROM candidates WHERE published<? OR (published IS NULL AND discovered<?)", (boundary, boundary))
            report["stateCounts"] = {row["status"]: row["n"] for row in database.execute("SELECT status, COUNT(*) AS n FROM candidates GROUP BY status")}
        except AccessBlocked as error:
            report["blocked"] = str(error)
        finally:
            fetcher.fetch_bytes = original
    report["publicRequests"] = limiter.count
    report["finishedAt"] = datetime.now(CHINA).isoformat()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--discover-only", action="store_true", help="只发现 BV，不访问后端或抓正文")
    parser.add_argument("--dry-run", action="store_true", help="发现并核验详情和指数，不访问后端或抓正文")
    parser.add_argument("--skip-discovery", action="store_true", help="仅处理状态库中到期候选")
    parser.add_argument("--report", type=Path, help="本轮汇总 JSON")
    args = parser.parse_args()
    try:
        report = run(load_config(args.config), discover_only=args.discover_only, dry_run=args.dry_run, skip_discovery=args.skip_discovery)
        text = json.dumps(report, ensure_ascii=False, indent=2)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(text, encoding="utf-8")
        print(text)
        return 2 if report.get("blocked") or report["failed"] or report["sourceFailures"] else 0
    except (ValueError, RuntimeError, OSError) as error:
        print(f"自动采集未完成：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
