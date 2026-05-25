<template>
  <div class="input-container">
    <div class="input-toolbar">
      <KnowledgeBaseToggle @uploaded="onUploaded" />
      <div class="toolbar-spacer"></div>
      <div class="toolbar-hint">Enter 发送 · Shift+Enter 换行 · Tab 切换模式</div>
    </div>
    <div class="input-wrapper">
      <textarea
        ref="inputRef"
        class="message-input"
        :placeholder="isLoading ? '等待回复...' : (currentMode === 'chat' ? '输入消息...' : '输入 / 命令 (/help 查看帮助)')"
        v-model="text"
        rows="1"
        :disabled="isLoading"
        @keydown="handleKeydown"
        @input="autoResize"
      ></textarea>
      <button class="btn-send" :disabled="isLoading || !text.trim()" @click="handleSend">➤</button>
    </div>
    <input type="file" ref="fileInputRef" style="display:none" accept=".txt,.md,.json,.xml" @change="onFileSelected" />
    <div class="input-footer-bar">
      <div class="mode-status" @click="toggle">
        <span class="mode-indicator">{{ currentMode === 'chat' ? '💬' : '📝' }}</span>
        {{ currentMode === 'chat' ? '聊天模式' : '复习本模式' }}
        <span class="status-hint">· Tab 切换</span>
      </div>
      <div class="model-status" @click="toggleModel" v-if="currentId">
        <span>🤖</span>
        {{ currentModel === 'ollama' ? 'Ollama' : 'DeepSeek' }}
        <span class="status-hint">· 点击切换</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { useConversations } from '../composables/useConversations.js'
import { useChat } from '../composables/useChat.js'
import { useReview } from '../composables/useReview.js'
import { useMode } from '../composables/useMode.js'
import KnowledgeBaseToggle from './KnowledgeBaseToggle.vue'

const { currentId, currentModel, updateModel } = useConversations()
const { send, isLoading: chatLoading, pushMessage, saveMessageToBackend } = useChat()
const { isLoading: reviewLoading, handleUpload, handleUploadFile, handleGrade, handleList, handleHelp, handleSelect } = useReview()
const { currentMode, toggle } = useMode()

const text = ref('')
const inputRef = ref(null)
const fileInputRef = ref(null)

const isLoading = computed(() =>
  currentMode.value === 'chat' ? chatLoading.value : reviewLoading.value
)

function handleKeydown(e) {
  if (e.key === 'Tab') {
    e.preventDefault()
    toggle()
    return
  }
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function autoResize(e) {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 180) + 'px'
}

async function _pushAndSave(role, content) {
  pushMessage(role, content)
  await saveMessageToBackend(role, content)
}

function onUploaded() {
  // 上传完成后可刷新状态等
}

async function toggleModel() {
  if (!currentId.value) return
  const newModel = currentModel.value === 'ollama' ? 'deepseek' : 'ollama'
  await updateModel(currentId.value, newModel)
}

async function onFileSelected(e) {
  const file = e.target.files?.[0]
  if (!file) return
  await handleUploadFile(file)
  e.target.value = ''
}

async function handleSend() {
  const msg = text.value.trim()
  if (!msg) return
  if (currentMode.value === 'chat' && chatLoading.value) return
  if (currentMode.value === 'review' && reviewLoading.value) return

  text.value = ''
  if (inputRef.value) {
    inputRef.value.style.height = 'auto'
  }

  if (currentMode.value === 'chat') {
    await send(currentId.value, msg)
  } else {
    await handleReviewCommand(msg)
  }
}

async function handleReviewCommand(msg) {
  if (!msg.startsWith('/')) {
    await _pushAndSave('USER', msg)
    await _pushAndSave('ASSISTANT', '⚠️ 复习本模式下请输入 `/` 命令。输入 `/help` 查看帮助，或按 Tab 切回聊天模式')
    return
  }

  const spaceIdx = msg.indexOf(' ')
  const cmd = spaceIdx > 0 ? msg.slice(1, spaceIdx) : msg.slice(1)
  const args = spaceIdx > 0 ? msg.slice(spaceIdx + 1).trim() : ''

  switch (cmd) {
    case 'upload':
      if (args) {
        await handleUpload(args)
      } else {
        fileInputRef.value?.click()
      }
      break
    case 'review':
      if (args) {
        await handleGrade(args)
      } else {
        await _pushAndSave('ASSISTANT', '⚠️ 请输入答案，格式：\n1. 答案一\n2. 答案二')
      }
      break
    case 'list':
      await handleList()
      break
    case 'select':
      if (args) {
        const id = parseInt(args)
        if (isNaN(id)) {
          await _pushAndSave('ASSISTANT', '⚠️ 请输入有效的笔记ID，例如 `/select 42`')
        } else {
          await handleSelect(id)
        }
      } else {
        await _pushAndSave('ASSISTANT', '⚠️ 请输入笔记ID，例如 `/select 42`（用 `/list` 查看）')
      }
      break
    case 'models':
      if (!currentId.value) {
        await _pushAndSave('ASSISTANT', '⚠️ 请先创建对话')
        break
      }
      if (args === 'ollama' || args === 'deepseek') {
        await updateModel(currentId.value, args)
        await _pushAndSave('ASSISTANT', '✅ 已切换至 **' + (args === 'ollama' ? 'Ollama' : 'DeepSeek') + '** 模型')
      } else {
        await _pushAndSave('ASSISTANT', '当前模型: **' + (currentModel.value === 'ollama' ? 'Ollama' : 'DeepSeek') + '**\n可用命令: `/models ollama` 或 `/models deepseek`')
      }
      break
    case 'help':
      await handleHelp()
      break
    default:
      await _pushAndSave('ASSISTANT', '❓ 未知命令 `/`' + cmd + '`，输入 `/help` 查看帮助')
  }
}
</script>
