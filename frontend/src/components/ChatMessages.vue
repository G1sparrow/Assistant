<template>
  <div class="message-container" ref="container">
    <WelcomeScreen v-if="!hasMessages && !hasConversation && currentMode === 'chat'" />
    <div class="messages-area" v-else-if="hasMessages">
      <div v-for="(msg, i) in messages" :key="i">
        <MessageBubble :message="msg" />
      </div>
      <div v-if="showTyping" class="message message-assistant">
        <div class="message-avatar">🤖</div>
        <div class="message-body">
          <div class="message-header">{{ currentMode === 'chat' ? '小智' : '复习助手' }}</div>
          <div class="message-bubble">
            <div class="typing-inline">
              <span class="typing-dot"></span>
              <span class="typing-dot"></span>
              <span class="typing-dot"></span>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="!hasMessages && currentMode === 'review'" class="review-welcome-inline">
      <div class="review-welcome-icon">📝</div>
      <p>输入 <code>/upload</code> 上传笔记，<code>/review</code> 批改答案</p>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { useConversations } from '../composables/useConversations.js'
import { useChat } from '../composables/useChat.js'
import { useReview } from '../composables/useReview.js'
import { useMode } from '../composables/useMode.js'
import WelcomeScreen from './WelcomeScreen.vue'
import MessageBubble from './MessageBubble.vue'

const { currentId } = useConversations()
const { messages, isLoading: chatLoading } = useChat()
const { isLoading: reviewLoading } = useReview()
const { currentMode } = useMode()

const container = ref(null)

const isLoading = computed(() =>
  currentMode.value === 'chat' ? chatLoading.value : reviewLoading.value
)

const hasMessages = computed(() => messages.value.length > 0)
const hasConversation = computed(() => !!currentId.value)
const showTyping = computed(() =>
  isLoading.value && messages.value[messages.value.length - 1]?.role !== 'ASSISTANT'
)

watch([messages, isLoading], async () => {
  await nextTick()
  if (container.value) {
    container.value.scrollTop = container.value.scrollHeight
  }
}, { deep: true })
</script>
