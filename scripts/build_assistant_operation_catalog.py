"""按人工核对的页面能力生成固定目录，运行时不接受模型指定类名、方法名或地址。"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'backend/src/main/java/com/link/linkagent'
GROUPS = {
    'CreatorTaskController': ('tasks', '创作任务'),
    'CreatorInteractiveController': ('creative', '想法与发布方案'),
    'CreatorWorkflowController': ('workflow', '发布前沟通与方案'),
    'ProductionPlanController': ('blueprint', '制作蓝图'),
    'CreatorPrePublishSettingsController': ('taskSettings', '任务偏好设置'),
    'PrePublishSuggestionController': ('suggestion', '发布方案查询'),
    'TaskAutofillController': ('autofill', '辅助填写'),
    'CreatorBilibiliController': ('bilibili', 'B站账号与BV绑定'),
    'CreatorFeedbackController': ('feedback', '观众反馈'),
    'CreatorCompetitorController': ('competitor', '竞品分析'),
    'CreatorReportController': ('review', '总体复盘'),
    'DraftVideoProbeController': ('media', '成片与媒体探测'),
    'DraftVideoMediaController': ('mediaFiles', '媒体删除'),
    'MediaProcessingController': ('processing', '媒体预处理'),
    'PreflightReviewController': ('screening', '成片试映'),
    'PreflightReportController': ('screeningIssues', '试映问题与修改任务'),
    'CreatorMediaStatusController': ('mediaStatus', '媒体功能状态'),
    'MediaUploadController': ('uploads', '成片上传状态'),
    'KnowledgeReferenceVideoController': ('references', '参考案例库'),
    'KnowledgeReferenceAutoCollectionController': ('collection', '案例自动采集'),
    'KnowledgeReferenceMaintenanceController': ('maintenance', '案例库维护'),
    'MemoryController': ('memory', '长期记忆'),
    'SettingsController': ('settings', '平台设置'),
    'UserLlmConfigController': ('models', '模型与密钥配置'),
    'PromptTemplateController': ('prompts', '提示词模板'),
    'LlmApiUsageController': ('usage', '模型使用记录'),
    'CreatorProfileController': ('profile', '创作者画像'),
    'CreatorContextController': ('context', '创作者语境'),
    'CreatorPreferenceController': ('preferences', '创作偏好'),
    'CreatorEvaluationController': ('evaluation', '评测用例'),
}
LABELS = {
    'createTask': '创建有稿件材料的任务', 'updateTask': '修改任务与稿件', 'skipToPreflight': '已有成片，跳过方案进入试映',
    'deleteTask': '删除任务', 'importMaterial': '导入稿件文件', 'listTasks': '列出任务', 'getTask': '读取任务',
    'createInteractiveTask': '根据想法创建任务', 'getInteractiveTask': '读取创作想法与方案状态',
    'uploadContextDocuments': '上传背景资料', 'triggerUnderstanding': '理解创作想法', 'generateCreativeOptions': '生成发布方案',
    'regenerateOptions': '重新生成发布方案', 'confirmOption': '确认选中的发布方案',
    'startPrePublishWorkflow': '创建或恢复发布前沟通', 'listMessages': '读取沟通记录', 'listSteps': '读取执行步骤',
    'getWorkflowUsage': '读取工作流用量', 'sendMessage': '发送创作要求', 'alignPrePublishIntent': '对齐创作要求',
    'analyzePrePublishWorkflow': '生成发布前方案', 'generatePrePublishManuscriptDraft': '生成文稿草稿',
    'confirmPrePublishSuggestion': '确认发布前方案', 'getSuggestion': '读取发布方案',
    'getCurrent': '读取当前结果', 'create': '创建并生成', 'updateStep': '更新制作步骤',
    'getSettings': '读取任务设置', 'saveSettings': '保存任务设置', 'autofillField': '生成辅助填写内容',
    'bindAccount': '绑定B站公开账号', 'getAccount': '读取绑定账号', 'syncVideos': '同步公开投稿',
    'getLinkedVideos': '列出公开投稿', 'bindBvToTask': '绑定已发布视频BV',
    'getPostPublishReadiness': '检查发布后门禁', 'getTaskBinding': '读取任务BV绑定',
    'saveFeedback': '保存观众反馈', 'getFeedback': '读取反馈原始材料', 'analyze': '生成分析报告',
    'getReport': '读取分析报告', 'importFeedback': '导入反馈文件', 'fetchFeedback': '采集已发布视频反馈',
    'getDashboard': '读取反馈看板', 'chat': '追问反馈报告', 'rebuildEvidenceIndex': '重建反馈证据索引',
    'getEvidenceIndexStatus': '读取反馈索引状态', 'saveCompetitorVideo': '保存竞品材料',
    'getCompetitorVideo': '读取竞品材料', 'analyzeByReferenceVideo': '按案例生成竞品分析',
    'listHistory': '读取复盘历史', 'getHistory': '读取指定历史复盘', 'exportMarkdown': '导出复盘正文',
    'getCurrentDraftVideo': '读取当前成片', 'getDraftVideo': '读取成片版本', 'probeDraftVideo': '探测成片信息',
    'deleteMedia': '删除原片与派生媒体', 'estimate': '估算媒体处理费用', 'createJob': '启动媒体预处理',
    'getCurrentJob': '读取媒体处理进度', 'retryJob': '重试媒体处理', 'get': '读取指定试映',
    'cancel': '取消试映', 'retry': '重试试映', 'completeScreening': '补全观众试映',
    'updateIssue': '确认或暂不采纳试映问题', 'listEditTasks': '读取修改任务', 'updateEditTask': '更新修改任务',
    'getStatus': '读取媒体功能状态', 'getUpload': '读取上传会话', 'getCurrentUpload': '读取当前上传',
    'abortUpload': '取消成片上传', 'importReferenceVideos': '导入参考案例',
    'fetchAndImportReferenceVideo': '采集并导入参考视频', 'refreshReferenceVideoPublicMetadata': '刷新案例公开数据',
    'recomputeReferenceVideoQuality': '重算案例指数', 'searchReferenceVideos': '检索案例',
    'topicSearchReferenceVideos': '按主题检索案例', 'referenceVideoAnalysisContext': '读取案例证据',
    'listReferenceVideos': '浏览案例库', 'status': '读取状态', 'start': '启动自动采集', 'stop': '停止自动采集',
    'backfill': '回补参考视频资料', 'purge': '清理过期案例', 'saveLongTermMemory': '保存长期记忆',
    'listLongTermMemories': '列出长期记忆', 'getLongTermMemory': '读取一条记忆',
    'deleteLongTermMemory': '删除长期记忆', 'restoreLongTermMemory': '恢复长期记忆',
    'updateToggle': '修改平台功能开关', 'updateValue': '修改平台配置值', 'checkConnectivity': '检查依赖连通性',
    'listConfigs': '读取脱敏模型配置', 'saveConfig': '保存模型与密钥配置', 'deleteConfig': '删除模型配置',
    'testConnectivity': '测试模型连通性', 'listPromptTemplates': '列出提示词', 'getPromptTemplate': '读取提示词',
    'updatePromptTemplate': '修改提示词', 'summarizeTask': '读取任务用量汇总', 'listTaskCalls': '读取任务调用记录',
    'listCalls': '读取全局调用记录', 'getProfile': '读取创作者画像', 'recordFeedbackEvent': '记录偏好反馈',
    'refreshProfile': '刷新创作者画像', 'saveTerm': '保存创作语境', 'listTerms': '列出创作语境',
    'buildBundle': '读取创作语境组合', 'disableTerm': '停用创作语境', 'recordFeedback': '记录语境采纳反馈',
    'listPreferences': '读取创作偏好', 'createCase': '创建评测用例', 'listCases': '列出评测用例',
    'getCase': '读取评测用例', 'recordResult': '记录评测结果', 'listResults': '读取评测结果',
    'comparePromptVersions': '对比提示词版本',
    'rebuildReferenceVideoIndex': '重建案例向量索引', 'referenceVideoIndexStatus': '读取案例向量索引状态',
    'rebuildReferenceVideoChunkIndex': '重建案例主题块索引', 'referenceVideoChunkIndexStatus': '读取案例主题块索引状态',
    'rebuildReferenceVideoItemIndex': '重建案例评论弹幕索引', 'referenceVideoItemIndexStatus': '读取案例评论弹幕索引状态',
    'rebuildReferenceVideoHybridIndex': '重建案例混合检索索引', 'referenceVideoHybridIndexStatus': '读取案例混合索引状态',
    'rebuildReferenceVideoItemHybridIndex': '重建案例评论混合索引', 'referenceVideoItemHybridIndexStatus': '读取案例评论混合索引状态',
}
SKIP = {'events', 'subscribeEvents', 'signParts', 'registerCompletedParts', 'completeUpload', 'createUpload', 'listParts', 'createAssetReadUrl'}
READ_POST = {'searchReferenceVideos', 'topicSearchReferenceVideos', 'estimate'}
result = []
for p in sorted(BASE.rglob('*Controller.java')):
    if p.stem not in GROUPS:
        continue
    text = p.read_text(encoding='utf-8')
    package = re.search(r'package ([\w.]+);', text)[1]
    group, group_label = GROUPS[p.stem]
    for match in re.finditer(r'public\s+[\w<>?,. \[\]]+\s+(\w+)\s*\(', text):
        name = match[1]
        if name in SKIP:
            continue
        start = text.rfind('\n    }', 0, match.start())
        prefix = text[max(start, 0):match.start()]
        mapping = re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping', prefix)
        if not mapping:
            continue
        label = LABELS.get(name)
        if label is None and 'Index' in name:
            label = ('重建' if name.startswith('rebuild') else '读取') + re.sub(r'(?<!^)([A-Z])', r' \1', name)
        if label is None:
            raise ValueError(f'请补充操作说明: {p.stem}.{name}')
        if p.stem == 'ProductionPlanController' and name == 'create': label = '生成制作蓝图'
        if p.stem == 'PreflightReviewController' and name == 'create': label = '启动成片试映'
        if name == 'analyze': label = group_label + '：生成分析'
        access = 'READ' if mapping[-1] == 'Get' or name in READ_POST or (p.stem == 'KnowledgeReferenceMaintenanceController' and name == 'status') else 'WRITE'
        result.append(dict(id=f'{group}.{name}', group=group_label, label=label, controller=f'{package}.{p.stem}', method=name, access=access))
result.append(dict(id='media.uploadVideo', group='成片上传', label='选择本地MP4并上传成片', controller='', method='', access='WRITE'))
out = ROOT / 'backend/src/main/resources/assistant/operations.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'已生成 {len(result)} 项固定操作，覆盖 {len(GROUPS)} 个业务控制器')
