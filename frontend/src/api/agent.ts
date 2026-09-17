import type {
  AgentChatResponse,
  AgentExecutionMode,
  SessionListItem,
  SessionMessageItem,
} from '@/types/agent'
import type { AgentStep } from '@/types/agent'
import { get, post } from './http'

/** SSE 排查日志统一前缀，浏览器控制台可按 [agent-sse] 过滤 */
const SSE_LOG_PREFIX = '[agent-sse]'
/** 连接建立后迟迟收不到任何事件就提醒一次：后端会先发 session 事件，正常应在一秒内到达 */
const FIRST_EVENT_WARN_TIMEOUT_MILLIS = 20_000
/** 日志里最多带多少字符的原始报文，够看出格式差异又不至于刷屏 */
const RAW_SAMPLE_MAX_LENGTH = 240

/**
 * 记录 SSE 排查日志。
 * 为什么所有异常都要落到控制台：之前流解析失败是静默跳过的，
 * 界面上只是"一直没反应"，事后完全查不到是断网、后端异常还是格式对不上。
 */
function warnSse(message: string, detail?: unknown) {
  if (detail === undefined) {
    console.warn(`${SSE_LOG_PREFIX} ${message}`)
    return
  }
  console.warn(`${SSE_LOG_PREFIX} ${message}`, detail)
}

/** 把原始报文压成单行并截断，避免把整段模型输出或用户数据打进日志 */
function clipRaw(value: string, maxLength = 160) {
  const oneLine = value.replace(/\s+/g, ' ').trim()
  return oneLine.length <= maxLength ? oneLine : `${oneLine.slice(0, maxLength)}...`
}

/**
 * 发送 Agent 消息。
 * userId 是后端 AgentChatRequest 新增字段（P0-3 阶段），
 * 用于将会话绑定到具体用户，支持长期记忆和画像查询。
 */
export function sendAgentMessage(
  sessionId: string,
  message: string,
  executionMode: AgentExecutionMode,
  userId?: string,
) {
  return post<AgentChatResponse>('/agent/chat', {
    sessionId: sessionId || undefined,
    message,
    executionMode,
    userId: userId || undefined,
  })
}

/** SSE 流式事件类型，与后端 AgentController.chatStream 的 SSE 事件一一对应 */
export type AgentStreamEvent =
  | { type: 'session'; sessionId: string }
  | { type: 'step'; step: AgentStep }
  | { type: 'token'; text: string }
  /** 思考增量：纯文本负载，与 token 同样不能走 JSON 解析 */
  | { type: 'thinking'; text: string }
  | { type: 'error'; message: string }
  | { type: 'done'; sessionId: string }
  /**
   * 上下文压缩：后端把较早历史合并成摘要后发出。
   * 前端据此在消息流里留下一条可见记录，用户不会只看到一个没有解释的停顿（与 DSH 在对话里保留压缩记录一致）。
   */
  | { type: 'context_compressed'; compressedMessageCount: number; tokensBefore: number; tokensAfter: number; summary: string }

/** SSE 流式事件处理器，由调用方提供业务逻辑 */
export interface AgentStreamHandlers {
  onSession?: (sessionId: string) => void
  onStep?: (step: AgentStep) => void
  onToken?: (text: string) => void
  /** 思考增量：只有模型开启思考模式并真的返回思考内容时才会触发 */
  onThinking?: (text: string) => void
  onError?: (message: string) => void
  onDone?: (sessionId: string) => void
  /** 上下文压缩：把较早历史合并为摘要时触发，summary 是摘要正文，可直接展示 */
  onContextCompressed?: (info: {
    compressedMessageCount: number
    tokensBefore: number
    tokensAfter: number
    summary: string
  }) => void
  /**
   * 非致命告警：连接长时间没有事件等异常情况。
   * 流不会因此中断，调用方提示作者去控制台看 [agent-sse] 日志即可。
   */
  onWarning?: (message: string) => void
}

/**
 * 流式发送 Agent 消息，通过 SSE 实时接收 ReAct 步骤与最终答案。
 * <p>
 * 使用 {@code fetch} + {@code ReadableStream} 而非 {@code EventSource}，
 * 因为 EventSource 仅支持 GET 请求，而我们需要 POST 发送消息体。
 * <p>
 * 返回一个 abort 函数，调用方可随时中断流式连接。
 * <p>
 * 容错与排查：单条脏数据只跳过不打断整条流，但会带上 {@code [agent-sse]} 前缀写进控制台；
 * 连接长时间没有任何事件、或流在未收到 done 事件前结束，会通过 onWarning / onError 通报调用方，
 * 避免出现"界面一直转圈但没人知道为什么"。
 *
 * @param sessionId 会话标识，为空时后端自动创建
 * @param message 用户消息
 * @param executionMode 执行模式
 * @param handlers 事件处理器
 * @param userId 用户标识（可选）
 * @returns abort 函数，调用后中断 SSE 连接
 */
export function sendAgentMessageStream(
  sessionId: string,
  message: string,
  executionMode: AgentExecutionMode,
  handlers: AgentStreamHandlers,
  userId?: string,
): () => void {
  const abortController = new AbortController()
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

  // 异步启动 fetch 流，不阻塞调用方
  void (async () => {
    try {
      const response = await fetch(`${baseUrl}/agent/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId: sessionId || undefined,
          message,
          executionMode,
          userId: userId || undefined,
        }),
        signal: abortController.signal,
      })

      if (!response.ok) {
        // 非 200 响应：尝试解析错误消息
        let errorMsg = `HTTP ${response.status}`
        try {
          const errorBody = await response.json()
          errorMsg = errorBody.message || errorBody.detail || errorBody.error || errorMsg
        } catch { /* 错误体非 JSON 时忽略 */ }
        warnSse(`流式接口返回 HTTP ${response.status}：${errorMsg}`)
        handlers.onError?.(errorMsg)
        return
      }

      if (!response.body) {
        const unsupportedMessage = '浏览器不支持 ReadableStream，无法接收流式响应'
        warnSse(unsupportedMessage)
        handlers.onError?.(unsupportedMessage)
        return
      }

      // 逐行解析 SSE 流（标准 SSE 格式：event: xxx\ndata: yyy\n\n）
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      /** 已收到的字节数：用来区分"连接断了"和"压根没数据" */
      let receivedBytes = 0
      /** 成功识别的事件数：收到了字节却一个事件都没识别出来，说明前后端格式对不上 */
      let parsedEventCount = 0
      /** 是否收到后端的收尾事件，没收到就说明流被超时、代理或异常截断了 */
      let sawDone = false
      /** 报文开头样本：格式对不上时，这段就是唯一的排查依据 */
      let rawSample = ''

      // 后端会先发 session 事件，正常应在一秒内到达；迟迟没有任何事件说明连接虽然建了但流不通
      // （例如反向代理缓冲了响应）。这里只提醒不主动断开，避免误伤本来就慢的推理。
      const silenceTimer = window.setTimeout(() => {
        const silenceMessage = `SSE 连接已建立，但 ${FIRST_EVENT_WARN_TIMEOUT_MILLIS / 1000} 秒内没有收到任何事件，请检查后端日志和代理是否缓冲了流式响应`
        warnSse(silenceMessage)
        handlers.onWarning?.(silenceMessage)
      }, FIRST_EVENT_WARN_TIMEOUT_MILLIS)

      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          receivedBytes += value.byteLength
          const text = decoder.decode(value, { stream: true })
          // 只留最开始的一小段原始报文，够看出格式差异即可
          if (rawSample.length < RAW_SAMPLE_MAX_LENGTH) {
            rawSample = (rawSample + text).slice(0, RAW_SAMPLE_MAX_LENGTH)
          }
          buffer += text
          // SSE 事件以空行分隔，规范同时允许 \n 和 \r\n 两种换行
          const parts = buffer.split(/\r?\n\r?\n/)
          // 最后一段可能不完整，保留到下次循环
          buffer = parts.pop() ?? ''

          for (const part of parts) {
            const event = parseSseEvent(part, warnSse)
            if (!event) continue

            parsedEventCount++
            // 收到第一个事件后不再提示"没有事件"：后面慢是推理慢，不是流不通
            window.clearTimeout(silenceTimer)
            if (event.type === 'done') {
              sawDone = true
            }

            // 根据事件类型分发到对应的 handler
            switch (event.type) {
              case 'session':
                handlers.onSession?.(event.sessionId)
                break
              case 'step':
                handlers.onStep?.(event.step)
                break
              case 'token':
                handlers.onToken?.(event.text)
                break
              // 思考增量：模型开启思考模式时才有，未开启时这个分支一次都不会走到
              case 'thinking':
                handlers.onThinking?.(event.text)
                break
              case 'error':
                handlers.onError?.(event.message)
                break
              case 'done':
                handlers.onDone?.(event.sessionId)
                break
              case 'context_compressed':
                handlers.onContextCompressed?.(event)
                break
            }
          }
        }

        // 后端 runStreaming 无论成功失败都会以 done 收尾；没收到就说明流被截断了。
        // 这里必须给出结论，否则界面会一直停在"正在生成"，作者根本不知道发生了什么。
        if (!sawDone) {
          const received = `已收到 ${receivedBytes} 字节、识别 ${parsedEventCount} 个事件`
          const detail = parsedEventCount === 0 && receivedBytes > 0
            ? `${received}，整条流没有解析出任何事件。报文开头：${clipRaw(rawSample, RAW_SAMPLE_MAX_LENGTH)}`
            : `${received}，连接在 done 事件之前结束`
          warnSse(detail)
          // 弹窗只放人话，原始报文已经写进控制台，避免提示被长文本塞满
          handlers.onError?.(`流式响应未正常结束（${received}），详情见控制台 [agent-sse] 日志`)
        }
      } finally {
        // 无论正常结束、异常还是中断，都要清掉看门狗定时器
        window.clearTimeout(silenceTimer)
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') {
        return // 用户主动中断，不是错误
      }
      const failureMessage = (error as Error).message || 'SSE 连接失败'
      // 原来这里只把错误抛给上层，控制台没有任何记录，事后无法判断是断网还是后端没响应
      warnSse(failureMessage, error)
      handlers.onError?.(failureMessage)
    }
  })()

  // 返回 abort 函数，供外部中断连接
  return () => abortController.abort()
}

/**
 * 解析单条 SSE 事件文本为 AgentStreamEvent 对象。
 * <p>
 * SSE 格式示例（冒号后的空格按规范是可选的，Spring 的 SseEmitter 就不写这个空格）：
 * <pre>
 * event:step
 * data:{"stepNumber":1,"thought":"...","action":"search","actionInput":"q","observation":"..."}
 * </pre>
 * <p>
 * 解析不了的内容返回 null 让上层跳过：单条脏数据不应该打断整条流。
 * 但跳过前必须通过 warn 记录，否则出现"整条流一个事件都没解析出来"时无从查起。
 */
function parseSseEvent(
  raw: string,
  warn: (message: string, detail?: unknown) => void,
): AgentStreamEvent | null {
  let eventType = ''
  let hasDataField = false
  const dataLines: string[] = []

  for (const line of raw.split(/\r?\n/)) {
    // 按第一个冒号切分字段。冒号后的空格**不能去掉**：
    // 我们的生产方是 Spring 的 SseEmitter，它写的是 "data:" + 原文，不额外补空格，
    // 所以 "data: world" 的正文就是 " world"（那个空格属于内容）。
    // 曾经这里无条件 replace(/^ /, '')，把每个以空格开头的分片都吃掉一个空格，
    // 结果英文正文和思考内容拼起来变成 "Theuserisaskingme"；中文分片通常不以空格开头，
    // 所以只在英文内容上暴露出来。已用 curl 抓原始字节确认（见 docs 记录）。
    const separator = line.indexOf(':')
    if (separator < 0) continue
    const field = line.slice(0, separator)
    const value = line.slice(separator + 1)
    if (field === 'event') {
      // 事件名不会有有意义的首尾空格，这里 trim 是安全的
      eventType = value.trim()
    } else if (field === 'data') {
      // 规范允许一个事件带多行 data，用换行拼回原文（Spring 发多行文本就是这么拆的）
      dataLines.push(value)
      hasDataField = true
    }
  }

  // 既没有事件名也没有数据的块是心跳或注释（如 ":heartbeat"），按规范正常跳过
  if (!eventType && !hasDataField) return null

  if (!eventType) {
    warn(`收到没有 event 名的数据块，已忽略：${clipRaw(dataLines.join('\n'))}`)
    return null
  }
  if (!hasDataField) {
    warn(`事件 ${eventType} 没有 data 行，已忽略`)
    return null
  }

  const dataStr = dataLines.join('\n')

  // token 与 thinking 事件由后端按纯文本片段发送，不能先 JSON.parse，
  // 否则中文和普通文本会被静默丢弃（曾经就是这么丢事件的）。
  if (eventType === 'token') {
    return { type: 'token', text: dataStr }
  }
  if (eventType === 'thinking') {
    return { type: 'thinking', text: dataStr }
  }

  try {
    const data = JSON.parse(dataStr)
    switch (eventType) {
      case 'session':
        return { type: 'session', sessionId: data?.sessionId ?? '' }
      case 'step':
        // 步骤数据会被直接渲染成界面元素，不是对象就按脏数据丢掉，别把渲染层带崩
        if (!data || typeof data !== 'object') {
          warn(`step 事件的 data 不是对象，已忽略：${clipRaw(dataStr)}`)
          return null
        }
        return { type: 'step', step: data as AgentStep }
      case 'context_compressed':
        // 数字字段统一 Number() 兜底：后端漏字段时展示 0，而不是把 undefined 渲染进界面
        return {
          type: 'context_compressed',
          compressedMessageCount: Number(data?.compressedMessageCount ?? 0),
          tokensBefore: Number(data?.tokensBefore ?? 0),
          tokensAfter: Number(data?.tokensAfter ?? 0),
          summary: typeof data?.summary === 'string' ? data.summary : '',
        }
      case 'error':
        return { type: 'error', message: typeof data === 'string' ? data : (data?.message ?? '未知错误') }
      case 'done':
        return { type: 'done', sessionId: data?.sessionId ?? '' }
      default:
        warn(`未识别的事件类型 ${eventType}，已忽略：${clipRaw(dataStr)}`)
        return null
    }
  } catch (error) {
    // JSON 解析失败：记日志后跳过，不打断整条流
    warn(`事件 ${eventType} 的 data 不是合法 JSON，已忽略：${clipRaw(dataStr)}`, error)
    return null
  }
}

export function loadAgentSessions() {
  return get<SessionListItem[]>('/agent/sessions')
}

export function loadAgentSessionMessages(sessionId: string) {
  return get<SessionMessageItem[]>(`/agent/sessions/${encodeURIComponent(sessionId)}`)
}
