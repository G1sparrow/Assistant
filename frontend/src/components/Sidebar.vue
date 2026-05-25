<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <div class="app-title">💬 小智助手</div>
      <button class="sidebar-nav-btn" :class="{ active: currentPage === 'library' }" @click="handleToggleLibrary">
        📚 学习资料库
      </button>
      <div class="new-chat-row" v-if="currentPage === 'messages'">
        <button class="btn-primary" @click="handleNew">+ 新对话</button>
      </div>
    </div>
    <div class="sidebar-content" v-if="currentPage === 'messages'">
      <div class="conversation-list">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conversation-item"
          :class="{ active: conv.id === currentId }"
          @click="select(conv.id)"
        >
          <span class="conversation-item-title">{{ conv.title }}</span>
          <span class="conversation-item-time">{{ formatTime(conv.updatedAt) }}</span>
          <button class="conversation-item-delete" @click.stop="handleDelete(conv.id)" title="删除">✕</button>
        </div>
      </div>
    </div>
    <div class="sidebar-footer" v-if="currentPage === 'messages'">
      <div class="status-info">
        <div class="status-info-left">
          <span class="status-dot" :class="{ error: !status.online }"></span>
          <span class="status-label">{{ status.online ? '已连接' : '断开' }}</span>
        </div>
        <span class="status-detail">{{ status.conversations }} 个对话</span>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { useConversations } from '../composables/useConversations.js'
import { useChat } from '../composables/useChat.js'
import { useStatus } from '../composables/useStatus.js'
import { useMode } from '../composables/useMode.js'

const { conversations, currentId, create, switchTo, remove, formatTime } = useConversations()
const { loadMessages, clear } = useChat()
const { status } = useStatus()
const { currentPage, openLibrary, closeLibrary } = useMode()

const modelType = 'ollama'

const emit = defineEmits(['conversation-changed'])

function handleToggleLibrary() {
  if (currentPage.value === 'library') {
    closeLibrary()
  } else {
    openLibrary()
  }
}

async function handleNew() {
  const id = await create(modelType)
  if (id) {
    clear()
    emit('conversation-changed')
  }
}

async function select(id) {
  if (id === currentId.value) return
  switchTo(id)
  clear()
  await loadMessages(id)
  emit('conversation-changed')
}

async function handleDelete(id) {
  if (!confirm('确定要删除该对话吗？')) return
  await remove(id)
  if (currentId.value && conversations.value.find(c => c.id === currentId.value)) {
    await loadMessages(currentId.value)
  } else {
    clear()
  }
  emit('conversation-changed')
}
</script>
