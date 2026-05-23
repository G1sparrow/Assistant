<template>
  <div class="input-container">
    <div class="input-toolbar">
      <KnowledgeBaseToggle @uploaded="onUploaded" />
      <div class="toolbar-spacer"></div>
      <div class="toolbar-hint">Enter 发送 · Shift+Enter 换行</div>
    </div>
    <div class="input-wrapper">
      <textarea
        ref="inputRef"
        class="message-input"
        :placeholder="isLoading ? '等待回复...' : '输入消息... (Enter 发送, Shift+Enter 换行)'"
        v-model="text"
        rows="1"
        :disabled="isLoading"
        @keydown="handleKeydown"
        @input="autoResize"
      ></textarea>
      <button class="btn-send" :disabled="isLoading || !text.trim()" @click="handleSend">➤</button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { useConversations } from '../composables/useConversations.js'
import { useChat } from '../composables/useChat.js'
import KnowledgeBaseToggle from './KnowledgeBaseToggle.vue'

const { currentId } = useConversations()
const { send, isLoading } = useChat()

const text = ref('')
const inputRef = ref(null)

function handleKeydown(e) {
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

function onUploaded() {
  // 上传完成后可刷新状态等
}

async function handleSend() {
  const msg = text.value.trim()
  if (!msg || isLoading.value) return

  text.value = ''
  if (inputRef.value) {
    inputRef.value.style.height = 'auto'
  }

  await send(currentId.value, msg)
}
</script>
