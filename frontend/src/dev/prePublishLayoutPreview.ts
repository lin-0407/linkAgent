import type {
  CreatorContextTerm,
  CreatorPreference,
  CreatorSuggestion,
  CreatorTask,
  CreatorTaskSummary,
  CreatorWorkflowMessage,
  CreatorWorkflowSession,
  CreatorWorkflowStep,
} from '@/types/creator'

/**
 * 构造发布方案布局预览数据。
 *
 * 预览数据只在 Vite 开发环境并显式携带查询参数时使用，目的是让低配置开发机在不启动后端、
 * 不调用模型的情况下检查真实工作台组件，避免为了视觉验收向正式业务加入无任务绕过逻辑。
 */
export function createPrePublishLayoutPreviewFixture() {
  const taskId = 'layout-preview-prepublish'
  const sessionId = 'layout-preview-session'
  const createTime = '2026-07-17T14:09:00'
  const updateTime = '2026-07-17T14:09:00'
  const manuscriptParagraph =
    '本期视频记录校园暴雨后的真实情况。我们从宿舍出发，经过积水路段前往食堂，重点观察道路水位、通行安全、食堂营业情况和同学们的应对方式。内容保持纪实表达，不夸大灾情，也不使用制造恐慌的标题。'

  const task: CreatorTask = {
    id: 1,
    taskId,
    userId: 'default',
    taskName: '学校最近下大雨把学校宿舍楼和食堂淹了',
    videoType: '生活记录',
    status: 'PRE_PUBLISH_ANALYZED',
    planningSkipped: false,
    createTime,
    updateTime,
    materials: [
      {
        id: 1,
        materialType: 'TITLE_DRAFT',
        content: '暴雨后的校园：宿舍到食堂一路都是水',
        createTime,
        updateTime,
      },
      {
        id: 2,
        materialType: 'DESCRIPTION_DRAFT',
        content: '记录校园暴雨积水后的真实通行情况，以及学生前往食堂时遇到的问题。',
        createTime,
        updateTime,
      },
      {
        id: 3,
        materialType: 'MANUSCRIPT',
        content: manuscriptParagraph.repeat(10),
        createTime,
        updateTime,
      },
    ],
  }

  const taskSummary: CreatorTaskSummary = {
    id: task.id,
    taskId,
    userId: task.userId,
    taskName: task.taskName,
    videoType: task.videoType,
    status: task.status,
    materialCount: task.materials.length,
    createTime,
    updateTime,
  }

  const suggestion: CreatorSuggestion = {
    id: 1,
    suggestionId: 'layout-preview-suggestion',
    taskId,
    contentSummary: '校园暴雨后，创作者实地记录宿舍、道路和食堂区域的积水与通行情况。',
    creatorDilemma: '既要呈现现场冲击力，又要避免标题夸张和信息失真。',
    audienceProfile: '关注校园生活、极端天气和真实记录的学生与年轻观众。',
    audienceHook: '从“还能不能去食堂”这个具体问题切入，让观众迅速理解现场处境。',
    contentPositioning: '克制、真实、有现场细节的校园生活记录。',
    sellingPoints: JSON.stringify([
      '真实校园暴雨现场',
      '宿舍到食堂的完整路线',
      '具体水位和通行细节',
    ]),
    riskPoints: JSON.stringify([
      '避免把局部积水表述成全校受灾',
      '注意保护同学隐私',
      '不要使用未经确认的灾情数据',
    ]),
    titleSuggestions: JSON.stringify([
      {
        title: '宿舍楼秒变黄河？我游着去食堂，水位直接到肚子',
        viewerPsychology: '好奇罕见校园场景，想确认是否真有这么夸张',
        clickReason: '设问和数字强化具体化冲击，制造视觉疑问',
        trustRisk: '“游着”可能被质疑夸张，实际是趟水，但可用画面佐证',
        bestScenario: '用户浏览推荐流，对校园生活类内容敏感',
        reason: '符合问句式加场景细节，用具体水位代替情绪词',
        risk: '避免标题党，需要视频第一时间展示水深证据',
      },
      {
        title: '三年一遇：暴雨把学校食堂淹了，我在污水里走了10分钟',
        viewerPsychology: '想看看罕见自然灾害如何影响日常，以及当事人状态',
        clickReason: '三年一遇增加稀缺感，污水里走了10分钟强化经历',
        trustRisk: '污水可能过于负面，让人感受到不适，但真实',
        bestScenario: '搜索暴雨淹学校等关键词时的结果页',
        reason: '用时间数字点明稀缺性，叙事感强，避免情绪渲染',
        risk: '可能显得干淡，缺少悬念，但能吸引务实观众',
      },
      {
        title: '大学生暴雨实录：下课发现宿舍楼在水里，食堂还能进吗？',
        viewerPsychology: '把观众带入“如果是我”的情境，关心食堂能否使用',
        clickReason: '问句切中生存需求，同时提供校园生活代入感',
        trustRisk: '可能被觉得小题大做，但真实暴雨中确实存在',
        bestScenario: '午餐或晚餐时间推荐，与观众生活节奏共鸣',
        reason: '结合了问句和生活化叙事，自然不造作，符合大学生日常语感',
        risk: '食堂能否进可能答案简单，悬念较弱',
      },
    ]),
    descriptionSuggestion:
      '一场暴雨让宿舍楼、道路和食堂周边出现积水。这期视频按真实路线记录现场水位、通行情况和学生们的应对方式。',
    actionableRevisionPlan: JSON.stringify([
      {
        target: '视频开头',
        problem: '现场冲击力出现较晚',
        action: '前 8 秒直接展示最深积水画面，并交代拍摄位置和时间',
        expectedEffect: '让标题信息立即得到画面验证',
      },
      {
        target: '风险说明',
        problem: '局部积水容易被理解为全校受灾',
        action: '在字幕中明确拍摄范围，不使用未经确认的数据',
        expectedEffect: '降低夸张和误导风险',
      },
    ]),
    tagSuggestions: JSON.stringify(['校园生活', '暴雨', '大学生', '生活记录', '校园实录']),
    partitionSuggestion: '生活 / 日常',
    evidenceRefs: JSON.stringify(['TITLE_DRAFT', 'DESCRIPTION_DRAFT', 'MANUSCRIPT']),
    missingInfo: null,
    generationMode: 'LAYOUT_PREVIEW',
    qualityStatus: 'PASS',
    auditReport: null,
    rawOutput: '{}',
    parseStatus: 'SUCCESS',
    createTime,
    updateTime,
  }

  const workflowMessages: CreatorWorkflowMessage[] = [
    {
      id: 1,
      messageId: 'layout-preview-message-1',
      sessionId,
      role: 'AGENT',
      content: '开始执行发布前优化分析，本轮会先读取任务材料，再生成建议。',
      contentType: 'TEXT',
      detailRefType: null,
      detailRefId: null,
      sequenceNo: 1,
      createTime,
    },
    {
      id: 2,
      messageId: 'layout-preview-message-2',
      sessionId,
      role: 'RESULT',
      content: '已生成发布前优化建议，建议先检查标题、简介和标签。',
      contentType: 'RESULT_CARD',
      detailRefType: 'SUGGESTION',
      detailRefId: suggestion.suggestionId,
      sequenceNo: 2,
      createTime,
    },
  ]

  const workflowSession: CreatorWorkflowSession = {
    id: 1,
    sessionId,
    taskId,
    stage: 'PRE_PUBLISH',
    status: 'CONFIRMED',
    userId: 'default',
    confirmedResultId: suggestion.suggestionId,
    planGenerationCount: 1,
    errorMessage: null,
    createTime,
    updateTime,
    messages: workflowMessages,
  }

  const workflowSteps: CreatorWorkflowStep[] = Array.from({ length: 20 }, (_, index) => ({
    id: index + 1,
    stepId: `layout-preview-step-${index + 1}`,
    sessionId,
    stepType: index === 19 ? 'CONFIRM_RESULT' : 'AGENT_REASONING',
    stepName: index === 19 ? '等待后续阶段' : `完成分析步骤 ${index + 1}`,
    status: index === 19 ? 'PENDING' : 'SUCCESS',
    inputSummary: null,
    outputSummary: index === 19 ? null : '步骤已完成',
    rawOutput: null,
    errorMessage: null,
    startTime: `2026-07-17T14:${String(index).padStart(2, '0')}:00`,
    endTime: index === 19 ? null : `2026-07-17T14:${String(index).padStart(2, '0')}:30`,
    createTime: `2026-07-17T14:${String(index).padStart(2, '0')}:00`,
  }))

  const creatorPreferences: CreatorPreference[] = [
    {
      id: 1,
      preferenceId: 'layout-preview-preference',
      userId: 'default',
      sourceTaskId: taskId,
      sourceReportId: 'layout-preview-report',
      preferenceContent: JSON.stringify([
        '[ADOPTED] 采用发布前优化建议：倾向问句式标题；标题含数字；中等长度标题（16-25字）',
        '[ADOPTED] 采用发布前优化建议：标题含数字；倾向疑问句式；短标题风格（≤15字）',
        '[ADOPTED] 采用发布前优化建议：倾向问句式标题；标题含数字；长标题风格（>25字）',
        '[ADOPTED] 采用发布前优化建议：标题含数字；偏教程/实用导向',
      ]),
      createTime,
      updateTime,
    },
  ]

  const creatorContextTerms: CreatorContextTerm[] = [
    ['校园实录', 'KEYWORD'],
    ['真实记录', 'TITLE_PATTERN'],
    ['避免标题党', 'TABOO'],
  ].map(([term, termType], index) => ({
    id: index + 1,
    termId: `layout-preview-term-${index + 1}`,
    userId: 'default',
    videoType: '生活记录',
    term: term!,
    termType: termType as CreatorContextTerm['termType'],
    polarity: termType === 'TABOO' ? 'NEGATIVE' : 'POSITIVE',
    sourceType: 'USER_SAVE',
    sourceTaskId: taskId,
    evidenceText: '用于发布方案布局预览',
    weight: 1,
    usageCount: 1,
    acceptCount: 1,
    rejectCount: 0,
    enabled: true,
    createTime,
    updateTime,
  }))

  return {
    task,
    taskSummary,
    suggestion,
    workflowMessages,
    workflowSession,
    workflowSteps,
    creatorPreferences,
    creatorContextTerms,
  }
}
