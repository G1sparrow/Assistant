<template>
  <div class="message" :class="isUser ? 'message-user' : 'message-assistant'">
    <div class="message-avatar">{{ isUser ? '👤' : '🤖' }}</div>
    <div class="message-body">
      <div class="message-header">{{ isUser ? '你' : '小智' }}</div>
      <div class="message-bubble" :class="{ streaming: isStreaming }">
        <div v-if="isUser" class="msg-text">{{ message.content }}</div>
        <div v-else class="msg-markdown" :class="{ 'streaming-cursor': isStreaming }" v-html="rendered"></div>
        <ToolCallCard v-for="(tc, i) in message.toolCalls" :key="i" :tool-call="tc" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps({
  message: { type: Object, required: true }
})

const isUser = computed(() => props.message.role === 'USER')
const isStreaming = computed(() => !isUser.value && props.message.content === '')

const rendered = computed(() => {
  if (!props.message.content) return ''
  const html = marked.parse(props.message.content, { breaks: true, gfm: true })
  const div = document.createElement('div')
  div.innerHTML = html
  div.querySelectorAll('pre code').forEach(block => {
    hljs.highlightElement(block)
  })
  return div.innerHTML
})
</script>

<style scoped>
.msg-text {
  white-space: pre-wrap;
  word-wrap: break-word;
  line-height: 1.65;
}
</style>
