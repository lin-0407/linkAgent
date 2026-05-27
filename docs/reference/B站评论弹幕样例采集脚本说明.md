# B 站评论弹幕样例采集脚本说明

## 功能定位

`scripts/bilibili_feedback_fetcher.py` 是本地辅助脚本，用于把用户指定 BV 的公开评论和弹幕整理成样例文件。

它不是后端生产能力，也不是批量爬虫。脚本不使用 Cookie，不处理登录态，不绕过平台风控。

## 使用方式

基础命令：

```bash
python scripts/bilibili_feedback_fetcher.py BVxxxx
```

控制采集规模：

```bash
python scripts/bilibili_feedback_fetcher.py BVxxxx --max-comments 20 --max-replies-per-comment 5 --max-danmaku 200
```

只拉取指定分 P 弹幕：

```bash
python scripts/bilibili_feedback_fetcher.py BVxxxx --page 2
```

拉取所有分 P 弹幕：

```bash
python scripts/bilibili_feedback_fetcher.py BVxxxx --all-pages
```

## 常用参数

| 参数 | 说明 |
|---|---|
| `--output-dir` | 输出目录，默认项目内 `exports/bilibili_feedback` |
| `--format` | `json`、`txt`、`both`，默认 `both` |
| `--max-comments` | 主楼评论数量上限 |
| `--max-replies-per-comment` | 每条主楼评论下的回复数量上限 |
| `--max-danmaku` | 每个分 P 弹幕数量上限 |
| `--page` | 只采集指定分 P |
| `--all-pages` | 采集所有分 P 弹幕 |
| `--delay` | 分页请求间隔秒数 |

## JSON 结构

```json
{
  "video": {},
  "comments": {
    "rootComments": [
      {
        "commentType": "root",
        "message": "主楼评论内容",
        "replyComments": [
          {
            "commentType": "reply",
            "message": "回复评论内容"
          }
        ]
      }
    ]
  },
  "danmaku": {
    "pages": [
      {
        "page": 1,
        "items": [
          {
            "progressText": "01:23.000",
            "text": "弹幕内容"
          }
        ]
      }
    ]
  }
}
```

## TXT 结构

TXT 文件按以下区块输出：

1. 视频信息。
2. 评论样例。
3. 主楼评论。
4. 回复评论。
5. 弹幕样例。
6. 采集提示。

这个格式方便人工检查，也可以临时复制到当前前端输入框中。

## 边界说明

F12 能看到接口，不等于平台承诺允许脚本化、批量化或绕登录采集。

因此脚本保持以下约束：

1. 单 BV 输入。
2. 默认限量。
3. 默认请求间隔。
4. 不读取浏览器 Cookie。
5. 接口被限制时不绕过。

脚本会使用 B 站公开网页接口所需的 WBI 签名参数。这个签名来自公开视频网页接口，不等同于登录 Cookie，也不提供账号权限。

## 与后端关系

当前后端评论弹幕分析接口仍只接收用户主动提供的样例文本。

本脚本产物属于用户本地准备的数据，可以人工检查后再粘贴到工作台。后续如果要做导入接口，需要单独设计入参校验、大小限制和错误处理。
