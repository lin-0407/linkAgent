"""固定 CLI 入口；程序失败以结构化错误退出，不输出部署环境或完整异常。"""

import argparse
import json
import sys
from pathlib import Path

from .contract import RenderError


def offline_audit(event, args):
    # Python 侧不允许发起网络请求；编码器另由固定协议白名单及容器断网约束。
    if event in ("socket.connect", "socket.getaddrinfo", "socket.bind"):
        raise RenderError("NETWORK_FORBIDDEN", "渲染器禁止网络访问")
    if event == "open" and isinstance(args[0], (str, bytes)):
        import os
        name = Path(os.fsdecode(args[0])).name.lower()
        if name == ".env" or name.startswith(".env.") or name.endswith((".pem", ".key")):
            raise RenderError("SECRET_FILE_FORBIDDEN", "渲染器禁止读取密钥文件")


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("render")
    command.add_argument("--input", required=True, type=Path)
    command.add_argument("--work-root", required=True, type=Path)
    command.add_argument("--output-dir", required=True, type=Path)
    command.add_argument("--font", type=Path, default=Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
    command.add_argument("--ffmpeg", default="ffmpeg")
    command.add_argument("--ffprobe", default="ffprobe")
    command.add_argument("--timeout-seconds", type=int, default=600)
    check = commands.add_parser("check-env")
    check.add_argument("--font", type=Path, required=True)
    check.add_argument("--ffmpeg", default="ffmpeg")
    check.add_argument("--ffprobe", default="ffprobe")
    args = parser.parse_args()
    sys.addaudithook(offline_audit)
    try:
        if args.command == "check-env":
            from .environment import check_environment
            check_environment(args.font, args.ffmpeg, args.ffprobe)
            print(json.dumps({"status": "READY"}))
            return 0
        from .engine import render
        if not 1 <= args.timeout_seconds <= 3600:
            raise RenderError("INVALID_INPUT", "超时范围应为 1 到 3600 秒")
        result = render(args.input, args.work_root, args.output_dir, args.font, args.ffmpeg, args.ffprobe, args.timeout_seconds)
        print(json.dumps({"status": "COMPLETED", "manifest": "manifest.json", "inputHash": result["inputHash"]}))
        return 0
    except RenderError as error:
        print(json.dumps({"status": "FAILED", "code": error.code, "message": str(error)}, ensure_ascii=False))
        return error.exit_code
    except (OSError, ValueError, KeyError, TypeError, ImportError) as error:
        print(json.dumps({"status": "FAILED", "code": "RENDER_FAILED", "message": "工程或本地环境处理失败"}, ensure_ascii=False))
        return 4


if __name__ == "__main__":
    raise SystemExit(main())
