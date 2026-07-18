// ============================================================================
// useParticleNetwork — 灵感粒子网络 Canvas 动效
//
// 设计理念：
//   - 粒子 = 创作者脑海中的灵感碎片，不断游走
//   - 连线 = AI Agent 串联创意，有"方生方死"的生命节律（浮现 → 闪光 → 稳定 → 消亡）
//   - 配色引用项目 CSS 变量（--bili-blue），自动跟随主题
//
// 用法：
//   const { mount, unmount, setEnabled } = useParticleNetwork(canvasRef)
//   onMounted(() => mount())
//   onUnmounted(() => unmount())
// ============================================================================

import { ref, type Ref } from 'vue'

// ---- 粒子数据结构 ----
interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  r: number
  baseR: number
  phase: number
  breatheSpeed: number
  wanderAngle: number
  wanderTimer: number
}

// ---- 连线记忆 ----
interface Connection {
  from: Particle
  to: Particle
  opacity: number
  wasAlive: boolean
  birthGlow: number
  midPhase: number
  midAmp: number
  midSpeed: number
}

// ---- 涟漪 ----
interface Ripple {
  x: number
  y: number
  r: number
  maxR: number
  alpha: number
  speed: number
}

// ---- 信号点 ----
interface SignalDot {
  from: Particle
  to: Particle
  t: number
  speed: number
  forward: boolean
}

// ---- 鼠标状态 ----
interface MouseState {
  x: number
  y: number
  active: boolean
  settled: boolean
}

// ---- 可选参数 ----
export interface ParticleNetworkOptions {
  /** 粒子密度：每 N px² 一个粒子，默认 12000 */
  density?: number
  /** 连线最大距离，默认 135 */
  linkDist?: number
  /** 是否启用，默认 true */
  enabled?: boolean
}

// ---- 默认值 ----
const DEFAULTS: Required<ParticleNetworkOptions> = {
  density: 12000,
  linkDist: 135,
  enabled: true,
}

export function useParticleNetwork(
  canvasRef: Ref<HTMLCanvasElement | null>,
  options: ParticleNetworkOptions = {},
) {
  const opts = { ...DEFAULTS, ...options }
  const enabled = ref(opts.enabled)

  // ---- 内部状态 ----
  let ctx: CanvasRenderingContext2D | null = null
  let W = 0
  let H = 0
  let rafId = 0
  let particles: Particle[] = []
  let ripples: Ripple[] = []
  let signals: SignalDot[] = []
  let connections = new Map<string, Connection>()
  let mouse: MouseState = { x: -9999, y: -9999, active: false, settled: false }
  let mouseSettleTimer: ReturnType<typeof setTimeout> | null = null
  let mounted = false
  let hidden = typeof document !== 'undefined' ? document.hidden : false
  let reduceMotionQuery: MediaQueryList | null = null
  let accentColor = '#00aeec'

  // ---- 从 CSS 变量读取项目配色 ----
  function getAccentColor(): string {
    if (typeof window === 'undefined') return '#00aeec'
    const style = getComputedStyle(document.documentElement)
    return style.getPropertyValue('--bili-blue').trim() || '#00aeec'
  }

  // ---- 连线参数 ----
  const LINK_FADE_IN_SPEED = 0.07
  const LINK_FADE_OUT_SPEED = 0.04
  const MOUSE_ACTIVATE_RADIUS = 180
  const MOUSE_ATTRACT_RADIUS = 160
  const ATTRACT_STRENGTH = 0.04

  // ---- 工具函数 ----
  function pairKey(i: number, j: number): string {
    return i < j ? `${i}-${j}` : `${j}-${i}`
  }

  // ---- 创建粒子 ----
  function createParticles() {
    const count = Math.floor((W * H) / opts.density)
    particles = []
    connections.clear()
    signals.length = 0

    for (let i = 0; i < count; i++) {
      const p: Particle = {
        x: Math.random() * W,
        y: Math.random() * H,
        vx: (Math.random() - 0.5) * 0.8,
        vy: (Math.random() - 0.5) * 0.8,
        r: Math.random() * 1.8 + 0.6,
        baseR: 0,
        phase: Math.random() * Math.PI * 2,
        breatheSpeed: Math.random() * 0.008 + 0.004,
        wanderAngle: Math.random() * Math.PI * 2,
        wanderTimer: Math.random() * 60,
      }
      p.baseR = p.r
      particles.push(p)
    }
  }

  // ---- 更新粒子物理 ----
  function updateParticles() {
    for (const p of particles) {
      // 鼠标吸引
      if (mouse.active && !mouse.settled) {
        const dx = mouse.x - p.x
        const dy = mouse.y - p.y
        const dist = Math.sqrt(dx * dx + dy * dy)
        if (dist < MOUSE_ATTRACT_RADIUS && dist > 1) {
          const closeness = 1 - dist / MOUSE_ATTRACT_RADIUS
          const force = ATTRACT_STRENGTH * closeness * closeness
          p.vx += (dx / dist) * force
          p.vy += (dy / dist) * force
        }
      }

      // 漫游行为
      p.wanderTimer--
      if (p.wanderTimer <= 0) {
        const currentAngle = Math.atan2(p.vy, p.vx)
        const turn = (Math.random() - 0.5) * Math.PI * 0.66
        p.wanderAngle = currentAngle + turn
        p.wanderTimer = 40 + Math.random() * 80
      }
      const wanderForce = 0.008
      p.vx += Math.cos(p.wanderAngle) * wanderForce
      p.vy += Math.sin(p.wanderAngle) * wanderForce

      // 阻尼
      p.vx *= 0.93
      p.vy *= 0.93

      // 速度钳制
      const speed = Math.sqrt(p.vx * p.vx + p.vy * p.vy)
      const maxSpeed = 1.2
      const minSpeed = 0.12
      if (speed > maxSpeed) {
        p.vx *= maxSpeed / speed
        p.vy *= maxSpeed / speed
      } else if (speed < minSpeed) {
        if (speed < 0.01) {
          const a = Math.random() * Math.PI * 2
          p.vx = Math.cos(a) * minSpeed
          p.vy = Math.sin(a) * minSpeed
        } else {
          p.vx *= minSpeed / speed
          p.vy *= minSpeed / speed
        }
      }

      p.x += p.vx
      p.y += p.vy

      // 边界环绕
      const margin = 20
      if (p.x < -margin) p.x = W + margin
      else if (p.x > W + margin) p.x = -margin
      if (p.y < -margin) p.y = H + margin
      else if (p.y > H + margin) p.y = -margin

      // 呼吸
      p.r = p.baseR + Math.sin(p.phase) * 0.4
      p.phase += p.breatheSpeed
    }
  }

  // ---- 连线记忆系统 ----
  function updateConnections() {
    const len = particles.length
    const seenKeys = new Set<string>()
    const linkDistSq = opts.linkDist * opts.linkDist

    for (let i = 0; i < len; i++) {
      const a = particles[i]
      if (!a) continue
      for (let j = i + 1; j < len; j++) {
        const b = particles[j]
        if (!b) continue
        const dx = a.x - b.x
        const dy = a.y - b.y
        const distSq = dx * dx + dy * dy
        const key = pairKey(i, j)
        const existing = connections.get(key)
        if (distSq >= linkDistSq && !existing) {
          continue
        }
        seenKeys.add(key)

        const dist = Math.sqrt(distSq)

        const targetOpacity = dist < opts.linkDist
          ? Math.pow(1 - dist / opts.linkDist, 1.5) * 0.55
          : 0

        // 显式标注类型：connections.get 返回 Connection | undefined，
        // 赋值分支后 conn 必为 Connection，但 TS 无法自动收窄 let 变量，需手动标注
        let conn: Connection | undefined = existing
        if (!conn) {
          conn = {
            from: a,
            to: b,
            opacity: 0,
            wasAlive: false,
            birthGlow: 0,
            midPhase: Math.random() * Math.PI * 2,
            midAmp: Math.random() * 4 + 1,
            midSpeed: Math.random() * 0.015 + 0.005,
          }
          connections.set(key, conn)
        }
        // 经上方分支后 conn 必然存在，用非空断言收窄，避免后续每处都判空
        const c = conn!

        // 平滑过渡
        if (targetOpacity > c.opacity) {
          c.opacity += (targetOpacity - c.opacity) * LINK_FADE_IN_SPEED
        } else {
          c.opacity += (targetOpacity - c.opacity) * LINK_FADE_OUT_SPEED
        }
        if (targetOpacity === 0 && c.opacity < 0.003) {
          connections.delete(key)
          continue
        }

        // 出生检测
        if (!c.wasAlive && c.opacity > 0.08) {
          c.wasAlive = true
          c.birthGlow = 1
        }
        if (c.opacity < 0.02) {
          c.wasAlive = false
        }
      }
    }

    // 清除远离的连线 + 闪光衰减
    for (const [key, conn] of connections) {
      if (conn.birthGlow > 0.001) {
        conn.birthGlow *= 0.88
      } else {
        conn.birthGlow = 0
      }

      if (!seenKeys.has(key)) {
        conn.opacity *= 0.92
      }
      if (conn.opacity < 0.003 && !seenKeys.has(key)) {
        connections.delete(key)
      }
    }
  }

  // ---- 信号点 ----
  function spawnSignals() {
    const maxSignals = Math.floor(particles.length * 0.12)
    if (signals.length >= maxSignals) return

    for (const [, conn] of connections) {
      const alive = conn.opacity > 0.15 && conn.birthGlow < 0.3
      if (!alive) continue
      if (Math.random() < 0.015 && signals.length < maxSignals) {
        signals.push({
          from: conn.from,
          to: conn.to,
          t: Math.random(),
          speed: Math.random() * 0.004 + 0.002,
          forward: Math.random() > 0.5,
        })
      }
    }
  }

  function updateSignals() {
    for (let i = signals.length - 1; i >= 0; i--) {
      const s = signals[i]
      // 倒序循环保证 i 在数组范围内，收窄类型让 TS 满意
      if (!s) continue
      if (s.forward) {
        s.t += s.speed
        if (s.t >= 1) s.t -= 1
      } else {
        s.t -= s.speed
        if (s.t <= 0) s.t += 1
      }
      if (Math.random() < 0.003) s.forward = !s.forward

      const dx = s.from.x - s.to.x
      const dy = s.from.y - s.to.y
      const dist = Math.sqrt(dx * dx + dy * dy)
      if (dist > opts.linkDist * 1.3) {
        signals.splice(i, 1)
      }
    }
  }

  // ---- 涟漪 ----
  function updateRipples() {
    for (let i = ripples.length - 1; i >= 0; i--) {
      const rp = ripples[i]
      // 倒序循环保证 i 在数组范围内，收窄类型让 TS 满意
      if (!rp) continue
      rp.r += rp.speed
      rp.alpha = 0.5 * (1 - rp.r / rp.maxR)
      if (rp.r >= rp.maxR || rp.alpha <= 0.01) {
        ripples.splice(i, 1)
      }
    }
  }

  // ---- 渲染 ----
  function draw() {
    if (!ctx) return
    const accent = accentColor

    ctx.clearRect(0, 0, W, H)
    // Canvas 背景透明，由 body/html 的 --canvas 底色透过即可
    // 粒子直接画在页面内容之上

    // 鼠标光晕
    if (mouse.active) {
      const glow = ctx.createRadialGradient(mouse.x, mouse.y, 0, mouse.x, mouse.y, MOUSE_ACTIVATE_RADIUS)
      glow.addColorStop(0, `${accent}0A`)   // rgba(accent, 0.04)
      glow.addColorStop(0.6, `${accent}04`) // rgba(accent, 0.015)
      glow.addColorStop(1, `${accent}00`)
      ctx.fillStyle = glow
      ctx.beginPath()
      ctx.arc(mouse.x, mouse.y, MOUSE_ACTIVATE_RADIUS, 0, Math.PI * 2)
      ctx.fill()
    }

    // 连线（中点波动 + 出生闪光）
    const time = performance.now() * 0.001
    for (const [, conn] of connections) {
      const visibleAlpha = Math.max(conn.opacity, conn.birthGlow * 0.6)
      if (visibleAlpha < 0.006) continue

      const fromMouseDist = Math.sqrt(
        (conn.from.x - mouse.x) ** 2 + (conn.from.y - mouse.y) ** 2,
      )
      const toMouseDist = Math.sqrt(
        (conn.to.x - mouse.x) ** 2 + (conn.to.y - mouse.y) ** 2,
      )
      const nearMouse = fromMouseDist < MOUSE_ACTIVATE_RADIUS || toMouseDist < MOUSE_ACTIVATE_RADIUS

      const birthBoost = conn.birthGlow * 0.5
      const finalAlpha = nearMouse
        ? Math.min(visibleAlpha * 1.8 + birthBoost, 0.7)
        : Math.min(visibleAlpha + birthBoost, 0.55)

      const baseWidth = nearMouse ? 0.8 : 0.35
      const birthWidth = conn.birthGlow * 1.5
      ctx.lineWidth = baseWidth + birthWidth

      // 连线使用 B 站青蓝，出生闪光稍微提亮，避免旧红色粒子破坏当前主题。
      const r = 0 + conn.birthGlow * 64
      const g = 174 + conn.birthGlow * 32
      const b = 236 + conn.birthGlow * 12
      ctx.strokeStyle = `rgba(${r},${g},${b},${finalAlpha})`

      // 中点 + 正弦波动
      const mx = (conn.from.x + conn.to.x) / 2
      const my = (conn.from.y + conn.to.y) / 2
      const dx = conn.to.x - conn.from.x
      const dy = conn.to.y - conn.from.y
      const len = Math.sqrt(dx * dx + dy * dy)
      if (len < 1) continue

      const px = -dy / len
      const py = dx / len
      const waveOffset = Math.sin(time * conn.midSpeed + conn.midPhase) * conn.midAmp
      const cpx = mx + px * waveOffset
      const cpy = my + py * waveOffset

      ctx.beginPath()
      ctx.moveTo(conn.from.x, conn.from.y)
      ctx.quadraticCurveTo(cpx, cpy, conn.to.x, conn.to.y)
      ctx.stroke()
    }

    // 信号流动点
    spawnSignals()
    for (const s of signals) {
      const sx = s.from.x + (s.to.x - s.from.x) * s.t
      const sy = s.from.y + (s.to.y - s.from.y) * s.t
      ctx.fillStyle = 'rgba(251,114,153,0.75)'
      ctx.beginPath()
      ctx.arc(sx, sy, 1.3, 0, Math.PI * 2)
      ctx.fill()
    }

    // 涟漪使用粉色做短暂反馈，和青蓝连线形成 B 站配色对比。
    for (const rp of ripples) {
      ctx.strokeStyle = `rgba(251,114,153,${rp.alpha})`
      ctx.lineWidth = 1.5
      ctx.beginPath()
      ctx.arc(rp.x, rp.y, rp.r, 0, Math.PI * 2)
      ctx.stroke()
    }

    // 粒子节点
    for (const p of particles) {
      let brightness = 1
      if (mouse.active) {
        const dx = p.x - mouse.x
        const dy = p.y - mouse.y
        const dist = Math.sqrt(dx * dx + dy * dy)
        if (dist < MOUSE_ACTIVATE_RADIUS) {
          brightness = 1 + (1 - dist / MOUSE_ACTIVATE_RADIUS) * 2.5
        }
      }

      // 光晕
      const particleGlow = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.r * 4)
      const glowAlpha = 0.25 * brightness
      particleGlow.addColorStop(0, `rgba(0,174,236,${glowAlpha})`)
      particleGlow.addColorStop(1, 'rgba(0,174,236,0)')
      ctx.fillStyle = particleGlow
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r * 4, 0, Math.PI * 2)
      ctx.fill()

      // 核心
      const coreAlpha = Math.min(0.75, 0.3 * brightness)
      ctx.fillStyle = `rgba(0,174,236,${coreAlpha})`
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r * brightness, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  // ---- 主循环 ----
  function loop() {
    if (!mounted || !enabled.value || hidden) {
      rafId = 0
      return
    }
    updateParticles()
    updateConnections()
    updateSignals()
    updateRipples()
    draw()
    rafId = requestAnimationFrame(loop)
  }

  function scheduleLoop() {
    if (rafId === 0 && mounted && enabled.value && !hidden) {
      rafId = requestAnimationFrame(loop)
    }
  }

  // ---- 事件处理 ----
  function onMouseMove(e: MouseEvent) {
    mouse.x = e.clientX
    mouse.y = e.clientY
    mouse.active = true
    if (mouseSettleTimer) clearTimeout(mouseSettleTimer)
    mouse.settled = false
    mouseSettleTimer = setTimeout(() => { mouse.settled = true }, 2000)
  }

  function onMouseLeave() {
    mouse.active = false
    mouse.settled = true
  }

  function onClick(e: MouseEvent) {
    ripples.push({
      x: e.clientX,
      y: e.clientY,
      r: 0,
      maxR: Math.min(W, H) * 0.22,
      alpha: 0.4,
      speed: 2,
    })
  }

  function onResize() {
    const canvas = canvasRef.value
    if (!canvas) return
    W = canvas.width = window.innerWidth
    H = canvas.height = window.innerHeight
    createParticles()
    accentColor = getAccentColor()
  }

  // ---- 可见性变化时暂停/恢复（节省资源） ----
  function onVisibilityChange() {
    hidden = document.hidden
    if (hidden && rafId !== 0) {
      cancelAnimationFrame(rafId)
      rafId = 0
      return
    }
    scheduleLoop()
  }

  // ---- 公开方法 ----
  function mount() {
    const canvas = canvasRef.value
    if (!canvas) return
    mounted = true

    reduceMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    if (reduceMotionQuery.matches) {
      enabled.value = false
    }

    ctx = canvas.getContext('2d')
    if (!ctx) return

    W = canvas.width = window.innerWidth
    H = canvas.height = window.innerHeight
    accentColor = getAccentColor()

    createParticles()

    // Canvas 需要 pointer-events: none 才不挡住页面操作，所以交互事件挂到 window。
    window.addEventListener('mousemove', onMouseMove, { passive: true })
    window.addEventListener('mouseleave', onMouseLeave, { passive: true })
    window.addEventListener('click', onClick, { passive: true })
    window.addEventListener('resize', onResize)
    document.addEventListener('visibilitychange', onVisibilityChange)

    scheduleLoop()
  }

  function unmount() {
    mounted = false
    if (rafId !== 0) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    if (mouseSettleTimer) clearTimeout(mouseSettleTimer)

    window.removeEventListener('mousemove', onMouseMove)
    window.removeEventListener('mouseleave', onMouseLeave)
    window.removeEventListener('click', onClick)
    window.removeEventListener('resize', onResize)
    document.removeEventListener('visibilitychange', onVisibilityChange)

    particles = []
    connections.clear()
    signals.length = 0
    ripples.length = 0
    ctx = null
  }

  function setEnabled(val: boolean) {
    enabled.value = val && !(reduceMotionQuery?.matches ?? false)
    if (!enabled.value && rafId !== 0) {
      cancelAnimationFrame(rafId)
      rafId = 0
      return
    }
    scheduleLoop()
  }

  return { mount, unmount, setEnabled, enabled }
}
