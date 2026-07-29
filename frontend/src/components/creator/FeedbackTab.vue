<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BilibiliAccountPanel from '@/components/creator/BilibiliAccountPanel.vue'
import BvBindingPanel from '@/components/creator/BvBindingPanel.vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import type { BilibiliAccount, TaskVideoBinding } from '@/types/creator'

const {
  openGuidanceEditor,
  feedbackDashboard,
  feedbackFetchResult,
  openResultModal,
  canEnterFeedback,
  feedbackImportFile,
  isImportingFeedback,
  isFetchingFeedback,
  importFeedbackFile,
  hasFeedbackSampleInput,
  isSavingFeedback,
  submitFeedback,
  canRunFeedbackAnalyze,
  runFeedbackAnalyze,
  isAnalyzingFeedback,
  feedbackScriptBv,
  fetchFeedbackByBv,
  feedbackScriptForm,
  selectedTaskId,
  handleFeedbackFileChange,
  feedbackForm,
  feedbackAnalyzeForm,
  feedback,
  formatDate,
  isActiveStepReadOnly,
} = useCreatorWorkspaceShell()

const bilibiliAccount = ref<BilibiliAccount | null>(null)
const taskVideoBinding = ref<TaskVideoBinding | null>(null)
const bindingRefreshKey = ref(0)
const feedbackBindingReady = computed(() => taskVideoBinding.value?.bindingStatus === 'BOUND')
const feedbackBvMatchesBinding = computed(
  () =>
    feedbackBindingReady.value &&
    feedbackScriptBv.value === taskVideoBinding.value?.bvid,
)

watch(selectedTaskId, () => {
  // 切换任务时先关闭旧任务的反馈入口，等待新任务绑定状态回读后再开放。
  taskVideoBinding.value = null
})

function handleAccountReady(account: BilibiliAccount | null) {
  bilibiliAccount.value = account
}

function handleBindingReady(binding: TaskVideoBinding | null) {
  taskVideoBinding.value = binding
  if (binding) feedbackScriptForm.bvInput = binding.bvid
}

function refreshBindingAfterSync() {
  // 同步会在后端更新 WAITING_VERIFY 状态，重建绑定面板以回读最新结果。
  taskVideoBinding.value = null
  bindingRefreshKey.value += 1
}
</script>

<template>
  <section class="creator-section">
    <div class="creator-section-head">
      <div>
        <h3>观众反馈</h3>
      </div>
      <div class="creator-action-row">
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="isActiveStepReadOnly"
          @click="openGuidanceEditor('feedback')"
        >
          分析偏好
        </button>
        <button
          v-if="feedbackDashboard || feedbackFetchResult"
          type="button"
          class="creator-secondary-action"
          @click="openResultModal('feedbackDashboard')"
        >
          查看导入结果
        </button>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="isActiveStepReadOnly || !feedbackBindingReady || !canRunFeedbackAnalyze"
          @click="runFeedbackAnalyze"
        >
          {{ isAnalyzingFeedback ? '分析中...' : '读懂反馈' }}
        </button>
      </div>
    </div>

    <BilibiliAccountPanel
      @account-ready="handleAccountReady"
      @sync-completed="refreshBindingAfterSync"
    />
    <BvBindingPanel
      v-if="selectedTaskId"
      :key="[selectedTaskId, bindingRefreshKey].join(':')"
      :task-id="selectedTaskId"
      :bilibili-uid="bilibiliAccount?.bilibiliUid"
      @binding-ready="handleBindingReady"
      @bound="handleBindingReady"
    />
    <p v-if="!feedbackBindingReady" class="creator-inline-note">
      请先实际发布视频并完成 BV 归属校验；校验通过后才会开放反馈采集、导入和分析。
    </p>

    <div class="creator-form-grid creator-feedback-form-grid">
      <article class="span-full creator-script-panel">
        <div class="creator-script-panel-head">
          <div>
            <span>粘贴视频链接 / BV</span>
            <p>输入单条视频链接或 BV 号后，系统会读取这条视频的评论和弹幕，并整理成反馈数据。</p>
          </div>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="
              isActiveStepReadOnly ||
              !canEnterFeedback ||
              !feedbackBindingReady ||
              !feedbackBvMatchesBinding ||
              !feedbackScriptBv ||
              isFetchingFeedback ||
              isImportingFeedback ||
              isSavingFeedback ||
              isAnalyzingFeedback
            "
            @click="fetchFeedbackByBv"
          >
            {{ isFetchingFeedback ? '读取中...' : '自动读取反馈' }}
          </button>
        </div>
        <label class="creator-script-main-input">
          <span>视频链接 / BV</span>
          <input
            v-model="feedbackScriptForm.bvInput"
            type="text"
            maxlength="200"
            :disabled="isActiveStepReadOnly || feedbackBindingReady"
            placeholder="BVxxxx 或 https://www.bilibili.com/video/BVxxxx"
          />
        </label>
        <details class="creator-advanced-panel">
          <summary>高级采集设置</summary>
          <div class="creator-script-grid">
            <label>
              <span>主楼评论数</span>
              <input
                v-model.number="feedbackScriptForm.maxComments"
                type="number"
                min="0"
                max="500"
                :disabled="isActiveStepReadOnly"
              />
            </label>
            <label>
              <span>每条回复数</span>
              <input
                v-model.number="feedbackScriptForm.maxRepliesPerComment"
                type="number"
                min="0"
                max="100"
                :disabled="isActiveStepReadOnly"
              />
            </label>
            <label>
              <span>弹幕数</span>
              <input
                v-model.number="feedbackScriptForm.maxDanmaku"
                type="number"
                min="0"
                max="2000"
                :disabled="isActiveStepReadOnly"
              />
            </label>
            <label>
              <span>输出格式</span>
              <select v-model="feedbackScriptForm.format" :disabled="isActiveStepReadOnly">
                <option value="both">JSON + TXT</option>
                <option value="json">只输出 JSON</option>
              </select>
            </label>
          </div>
        </details>
      </article>

      <article class="span-full creator-feedback-file-panel">
        <div>
          <span>上传文件</span>
          <p>导入已经整理好的评论或弹幕文件，支持 JSON/TXT。</p>
        </div>
        <label class="creator-file-field">
          <!-- 切换任务时重建文件输入框，避免浏览器保留上一个任务选择过的本地文件。 -->
          <input
            :key="selectedTaskId"
            type="file"
            accept=".json,.txt,application/json,text/plain"
            aria-label="上传评论或弹幕文件"
            :disabled="
              isActiveStepReadOnly ||
              !canEnterFeedback ||
              !feedbackBindingReady ||
              isImportingFeedback ||
              isFetchingFeedback ||
              isSavingFeedback ||
              isAnalyzingFeedback
            "
            @change="handleFeedbackFileChange"
          />
        </label>
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="
            isActiveStepReadOnly ||
            !canEnterFeedback ||
            !feedbackBindingReady ||
            !feedbackImportFile ||
            isImportingFeedback ||
            isFetchingFeedback ||
            isSavingFeedback ||
            isAnalyzingFeedback
          "
          @click="importFeedbackFile"
        >
          {{ isImportingFeedback ? '导入中...' : '导入文件' }}
        </button>
      </article>

      <details class="span-full creator-feedback-optional-panel">
        <summary>
          <span>手动粘贴反馈</span>
          <small>
            {{
              hasFeedbackSampleInput
                ? '已填写，可展开调整评论、弹幕或背景'
                : '低频入口，需要临时补样例时再展开'
            }}
          </small>
        </summary>
        <div class="creator-feedback-manual-grid">
          <label>
            <span>手动粘贴评论</span>
            <textarea
              v-model="feedbackForm.commentSamples"
              maxlength="20000"
              :disabled="isActiveStepReadOnly"
              placeholder="粘贴已整理的评论样例"
            ></textarea>
          </label>
          <label>
            <span>手动粘贴弹幕</span>
            <textarea
              v-model="feedbackForm.danmakuSamples"
              maxlength="20000"
              :disabled="isActiveStepReadOnly"
              placeholder="粘贴弹幕样例，可换行分隔"
            ></textarea>
          </label>
          <label class="span-full">
            <span>补充背景</span>
            <textarea
              v-model="feedbackForm.extraContext"
              maxlength="500"
              :disabled="isActiveStepReadOnly"
              placeholder="说明样例来源、时间段或反馈场景"
            ></textarea>
          </label>
        </div>
        <div class="creator-feedback-optional-actions">
          <button
            type="button"
            class="creator-secondary-action"
            :disabled="
              isActiveStepReadOnly ||
              !canEnterFeedback ||
              !feedbackBindingReady ||
              !hasFeedbackSampleInput ||
              isSavingFeedback ||
              isFetchingFeedback ||
              isImportingFeedback ||
              isAnalyzingFeedback
            "
            @click="submitFeedback"
          >
            {{ isSavingFeedback ? '保存中...' : '保存手动粘贴' }}
          </button>
        </div>
      </details>

      <details class="span-full creator-feedback-optional-panel">
        <summary>
          <span>本次分析补充要求</span>
          <small>分析重点和输出偏好，默认沿用上方“分析偏好”</small>
        </summary>
        <div class="creator-feedback-manual-grid">
          <label>
            <span>分析重点</span>
            <textarea
              v-model="feedbackAnalyzeForm.analysisFocus"
              maxlength="500"
              :disabled="isActiveStepReadOnly"
              placeholder="如：判断观众是否理解项目价值"
            ></textarea>
          </label>
          <label>
            <span>额外要求</span>
            <textarea
              v-model="feedbackAnalyzeForm.extraRequirement"
              maxlength="500"
              :disabled="isActiveStepReadOnly"
              placeholder="补充报告输出偏好"
            ></textarea>
          </label>
        </div>
      </details>
    </div>

    <p v-if="feedback" class="creator-inline-note">
      样例已于 {{ formatDate(feedback.updateTime) }} 保存，导入结果可通过上方按钮查看。
    </p>
  </section>
</template>
