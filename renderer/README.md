# LinkAgent 离线渲染器

固定工程媒体生成模块。接收后端解析好的本地工程与资产，生成完整 MP4、独立 SRT、封面、实际成片首中尾抽帧和 `manifest.json`。V1 支持图片与 WAV，V2 已增加视频片段、截取、完整显示/裁切、原声与旁白混音；0.3.0 增加受控单路背景音乐。模块不生成脚本、不调用模型或 TTS、不连接业务数据库和 OSS；本地检查不替代发布前完整试映。

## 环境与调用

使用 Python 3.12、FFmpeg/FFprobe、中文字体和 `requirements.txt` 中固定版本依赖。容器内安装 Noto CJK 字体及其系统包许可证；不复制 Windows 字体。

```powershell
docker build -t linkagent-renderer:local renderer
docker run --rm --network none --memory 512m --cpus 2 --pids-limit 64 --read-only --tmpfs /tmp:rw,size=64m --mount type=bind,source=E:/linkAgent/linkAgent/export/renderer-s2-20261020,target=/work linkagent-renderer:local render --input /work/rest/input.json --work-root /work --output-dir /work/rest/attempt-1 --timeout-seconds 600
```

原生命令为 `python -m linkagent_renderer render --input ... --work-root ... --output-dir ... --font ...`，在 `renderer` 目录运行或安装对应包路径。解释器、二进制路径和字体文件都是部署者配置，不能来自模型字段。Java 已有内部固定工程调度、自动蓝图与产物注册；通用公开整片制作入口仍关闭，尚未部署到作者服务。渲染器不直接开放业务 HTTP；简报、音乐设置、字幕导入与时长建议由 Java 后端受理。

工作根目录必须存在，输入和资产解析后须位于该根目录，输出须为全新尝试目录。拒绝覆盖旧输出和越界链接；成功前不生成 `manifest.json`。进程取消只终止本次创建的编码/探测子进程。

## RenderInputV1

严格拒绝未知字段，JSON 顶层字段如下：

| 字段 | 内容 |
| --- | --- |
| `schemaVersion` | 固定 1 |
| `runId/attemptId/storyboardId` | 1–64 位字母、数字、下划线或连字符 |
| `seed` | 0 到 2^31−1 的固定视觉种子 |
| `inputHash` | 排除本字段后，按下述跨语言规范生成 UTF-8 摘要 token 并计算 SHA-256 |
| `video` | width、height、fps、frameCount、codec、audioSampleRate、fontId |
| `assets` | id、相对 path、sha256、mime |
| `scenes` | 连续场景，起止为绝对帧，结束帧不包含在本场景 |
| `subtitles` | 绑定场景与精确旁白资产，起止为整数毫秒 |

`video` 支持 1280×720、1920×1080，24/25/30fps，H.264/yuv420p、48kHz 双声道 AAC，字体 ID 固定 `cjk_sans`。全片上限 180 秒、单场景上限 30 秒、最多 48 个场景。总时长严格为 `frameCount/fps`；V1 使用无重叠直切，暂不接收转场参数，不能由输入传任意 FFmpeg 滤镜。后续新增受控转场须单独明确重叠公式与测试。

0.2.1 起，摘要递归按对象键排序，字符串、整数、布尔、null 及容器分隔符沿用保留中文的紧凑 JSON；浮点数使用裸 token `f64:` 加 16 位小写 IEEE 754 双精度大端十六进制，拒绝非有限值。实际工程文件仍是普通 JSON，token 只用于摘要，不能把 token 写进数值字段。例如 `-0.0001` 在不同语言中的科学记数形式可以不同，但摘要一致；整数 `1` 与浮点 `1.0` 保持区分。Java 以实际写出再读取的 JSON 数值为准。

纯整数工程摘要保持不变。旧浮点工程的输入和内容摘要会变化，需从源重新准备工程并重新确认授权；不能自动修改旧授权来采纳新摘要。当前公开制作入口未开放、没有生产部署，不增加历史摘要兼容分支。Java 与 Python 的固定向量及实际冻结→重写→编码测试共同覆盖此协议。

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

## RenderInputV2 视频片段

顶层 `schemaVersion=2`，其余顶层及摘要算法沿用 V1。每个 VIDEO_CLIP 场景通过 `clip` 引用本地 MP4/MOV 资产，包含 `assetId/sourceStartMs/sourceEndMs/fit/audioMode/gainDb`。区间必须与实际视频时长和场景帧数对应；CONTAIN 完整显示，COVER 居中裁切铺满；MUTE、ORIGINAL、MIX 分别表示静音原声、只保留原声、原声加旁白。

V2 的 `voice=null` 表示没有旁白，原声字幕引用片段资产，旁白字幕继续引用 WAV。全片明确静音才允许零峰值输出；仍输出 AAC 音轨。没有对白可使用空字幕数组，不生成占位文字。源音轨延迟不被抹掉，所截区间没有原声或原声 cue 越出实际音轨范围时拒绝。

支持本地 H.264/HEVC、单视频流、至多一条音轨；源画面及像素比例规范化的中间画面均至多 800 万像素、帧率至多 60fps、源时长至多 30 分钟，仍受单资产 32MiB、总量 256MiB 限制。HDR、异常元数据和不支持编码需先规范化。素材上传和 ASR 由后端业务服务负责。

0.3.0 起 V2 可选顶层 `backgroundMusic`（省略或 null 表示无配乐），字段为 `assetId/sourceStartMs/sourceEndMs/gainDb/fadeInMs/fadeOutMs/ducking`。音乐必须为独占该角色的真实 PCM16 WAV，单文件上限 64MiB、源时长上限 20 分钟；其他资产仍为 32MiB、旁白仍最多 30 秒、合计仍为 256MiB。V1 拒绝音乐字段。选区须真实存在，长度对应整片且最多一帧误差；只读所选范围，不循环或变速，量化容差尾部可以静音。

音乐音量为 −36 至 0dB；淡入淡出各为 0–10000ms，合计不能超过选区。开启压低时，依据实际主音轨的 RMS 能量压低音乐约12dB，进入约20ms、恢复约180ms；不声称识别了人声。只加配乐不生成对白字幕，音乐不足给业务检查问题；混音继续做真实峰值、削波及整片解码验收，不自动削波或限幅掩盖过大音量。后端接口及范围见[音乐字幕说明](../docs/reference/音乐字幕与时长建议接口说明.md)。

视频片段标题仅作元数据，不参与可见文字排版或字体覆盖检查，真实字幕仍严格校验。CONTAIN 按实际像素比例直接计算目标框内尺寸；COVER 先在源内裁切再缩放，不构建可能因极端长宽比或旋转 SAR 膨胀的完整中间帧。

完整场景示例、声音组合及业务边界见 [接入说明](../docs/reference/视频片段合成接入说明.md)。V1 继续按原规则拒绝新增字段。

## 产物与错误

`manifest.json` 保存运行/尝试/工程身份、输入与字体摘要、渲染器版本、实际媒体参数、音轨峰值与 RMS、整片解码结果、文件大小/SHA-256 和警告。所有文件名均为相对本次输出目录的固定名称。下游必须验证清单与文件摘要，不能凭 `video.mp4` 存在就注册成功。

清单版本仍为 1；V2 输入额外记录 `inputSchemaVersion=2` 和实际 `clipSources`，下游必须检查与工程版本一致。空字幕只写一个空行的 SRT，不能冒充实际转写结果。

退出码：0 成功，2 输入/资产/布局不合法，3 字体或媒体环境缺失，4 编码或检查失败，124 超时，130 取消。stdout 返回一条结构化成功或失败 JSON；详细媒体错误留在尝试目录日志，不打印环境或密钥。

渲染按帧写入编码器，每次只持有当前图像、一个场景音频和一个图片缓存。每次尝试检查可用磁盘，编码输出和日志有大小限制；上线仍应沿示例限制容器内存、CPU、进程数、写目录和网络。Python 审计钩子禁止 socket 和 `.env/.pem/.key` 文件，编码器使用固定参数和协议白名单。

## 固定工程与测试

`examples/build_examples.py` 生成休息提醒、HTTP 请求和厨房整理三个不同主题的图文工程。Windows 的 `examples/synthesize_local.ps1` 仅为验收生成本地离线 WAV，音频来源写入 `audio-source.json`；不是产品配音适配器，也不会退回付费供应商。每条字幕从其独立音频采样数得到实际起止，再拼接场景旁白，避免按字数估时间。

```powershell
python renderer/examples/build_examples.py --work-root export/renderer-s2-20261020
powershell -NoProfile -ExecutionPolicy Bypass -File renderer/examples/synthesize_local.ps1 -WorkRoot export/renderer-s2-20261020
python renderer/examples/build_examples.py --work-root export/renderer-s2-20261020 --finish
docker run --rm --network none --memory 512m --cpus 2 --entrypoint python linkagent-renderer:local -m unittest discover -s tests -v
```

测试和样例输出必须区分：单元编码测试用固定测试音验证协议，跨主题样例用真实中文离线配音验证可观看成片。合成声音需在实际发布材料中按平台要求说明。当前结果见仓库 `docs/error/视频固定工程渲染S2验证记录.md`。
