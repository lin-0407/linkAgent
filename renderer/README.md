# LinkAgent 离线渲染器

S2 固定工程媒体生成模块。接收后端解析好的本地工程与资产，生成完整 MP4、独立 SRT、封面、实际成片首中尾抽帧和 `manifest.json`。不生成脚本、不调用模型或 TTS、不连接业务数据库和 OSS。整片本地检查不替代发布前完整试映。

## 环境与调用

使用 Python 3.12、FFmpeg/FFprobe、中文字体和 `requirements.txt` 中固定版本依赖。容器内安装 Noto CJK 字体及其系统包许可证；不复制 Windows 字体。

```powershell
docker build -t linkagent-renderer:s2 renderer
docker run --rm --network none --memory 512m --cpus 2 --pids-limit 64 --read-only --tmpfs /tmp:rw,size=64m --mount type=bind,source=E:/linkAgent/linkAgent/export/renderer-s2-20261020,target=/work linkagent-renderer:s2 render --input /work/rest/input.json --work-root /work --output-dir /work/rest/attempt-1 --timeout-seconds 600
```

原生命令为 `python -m linkagent_renderer render --input ... --work-root ... --output-dir ... --font ...`，在 `renderer` 目录运行或安装对应包路径。解释器、二进制路径和字体文件都是部署者配置，不能来自模型字段。Java 的业务启动和产物注册将在 S3 接入，本步没有对外业务 HTTP API 或 Schema 变更。

工作根目录必须存在，输入和资产解析后须位于该根目录，输出须为全新尝试目录。拒绝覆盖旧输出和越界链接；成功前不生成 `manifest.json`。进程取消只终止本次创建的编码/探测子进程。

## RenderInputV1

严格拒绝未知字段，JSON 顶层字段如下：

| 字段 | 内容 |
| --- | --- |
| `schemaVersion` | 固定 1 |
| `runId/attemptId/storyboardId` | 1–64 位字母、数字、下划线或连字符 |
| `seed` | 0 到 2^31−1 的固定视觉种子 |
| `inputHash` | 排除本字段后，以 UTF-8、对象键排序、无多余分隔空白、保留中文的 JSON 计算 SHA-256 |
| `video` | width、height、fps、frameCount、codec、audioSampleRate、fontId |
| `assets` | id、相对 path、sha256、mime |
| `scenes` | 连续场景，起止为绝对帧，结束帧不包含在本场景 |
| `subtitles` | 绑定场景与精确旁白资产，起止为整数毫秒 |

`video` 支持 1280×720、1920×1080，24/25/30fps，H.264/yuv420p、48kHz 双声道 AAC，字体 ID 固定 `cjk_sans`。全片上限 180 秒、单场景上限 30 秒、最多 48 个场景。总时长严格为 `frameCount/fps`；V1 使用无重叠直切，暂不接收转场参数，不能由输入传任意 FFmpeg 滤镜。后续新增受控转场须单独明确重叠公式与测试。

资产支持 PNG/JPEG 和 16 位 PCM WAV。音频声道 1 或 2，8–48kHz，单条不超过 30 秒；解码图片不超过 800 万像素，单资产不超过 32MiB，总量不超过 256MiB。摘要和实际格式均核对。每场景一条本地旁白，可在 0 到 −24dB 内衰减，不截断或加速旁白凑时长。

场景示例：

```json
{
  "id":"scene-1","startFrame":0,"endFrame":240,
  "layout":"SPLIT_CARD","background":"#102030",
  "title":"一次网页请求","body":["浏览器发起请求"],
  "imageAssetId":"picture",
  "voice":{"assetId":"voice-1","offsetMs":200,"gainDb":-1}
}
```

布局为 TITLE_CARD、IMAGE_CARD、SPLIT_CARD、COMPARE_CARD。全图布局不放正文；对比布局必须有两个项。白色标题仅接受足够暗的背景。文字以真实字体测量、最多两行字幕，溢出和缺字均在编码前拒绝，不能静默裁切。

字幕字段为 `id/sceneId/startMs/endMs/text/audioAssetId`。同一实际音频可以对应多条字幕，但字幕须位于其实际播放范围且不重叠。渲染器只验证确定性范围，不能独立证明文字是原音频的准确转写；实际业务由 S3/S4 提供核实过的 cue。烧录字幕和 SRT 使用同一组输入。

## 产物与错误

`manifest.json` 保存运行/尝试/工程身份、输入与字体摘要、渲染器版本、实际媒体参数、音轨峰值与 RMS、整片解码结果、文件大小/SHA-256 和警告。所有文件名均为相对本次输出目录的固定名称。下游必须验证清单与文件摘要，不能凭 `video.mp4` 存在就注册成功。

退出码：0 成功，2 输入/资产/布局不合法，3 字体或媒体环境缺失，4 编码或检查失败，124 超时，130 取消。stdout 返回一条结构化成功或失败 JSON；详细媒体错误留在尝试目录日志，不打印环境或密钥。

渲染按帧写入编码器，每次只持有当前图像、一个场景音频和一个图片缓存。每次尝试检查可用磁盘，编码输出和日志有大小限制；上线仍应沿示例限制容器内存、CPU、进程数、写目录和网络。Python 审计钩子禁止 socket 和 `.env/.pem/.key` 文件，编码器使用固定参数和协议白名单。

## 固定工程与测试

`examples/build_examples.py` 生成休息提醒、HTTP 请求和厨房整理三个不同主题的图文工程。Windows 的 `examples/synthesize_local.ps1` 仅为验收生成本地离线 WAV，音频来源写入 `audio-source.json`；不是产品配音适配器，也不会退回付费供应商。每条字幕从其独立音频采样数得到实际起止，再拼接场景旁白，避免按字数估时间。

```powershell
python renderer/examples/build_examples.py --work-root export/renderer-s2-20261020
powershell -NoProfile -ExecutionPolicy Bypass -File renderer/examples/synthesize_local.ps1 -WorkRoot export/renderer-s2-20261020
python renderer/examples/build_examples.py --work-root export/renderer-s2-20261020 --finish
docker run --rm --network none --memory 512m --cpus 2 --entrypoint python linkagent-renderer:s2 -m unittest discover -s tests -v
```

测试和样例输出必须区分：单元编码测试用固定测试音验证协议，跨主题样例用真实中文离线配音验证可观看成片。合成声音需在实际发布材料中按平台要求说明。当前结果见仓库 `docs/error/视频固定工程渲染S2验证记录.md`。
