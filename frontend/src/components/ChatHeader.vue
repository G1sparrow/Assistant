<template>
  <div class="chat-header">
    <div class="chat-header-status">
      <span class="status-item">
        <span class="status-item-dot" :class="ragClass"></span>
        <span class="status-item-text">{{ ragText }}</span>
      </span>
      <span class="status-item">
        <span class="status-item-dot" :class="mcpClass"></span>
        <span class="status-item-text">{{ mcpText }}</span>
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useConversations } from '../composables/useConversations.js'
import { useStatus } from '../composables/useStatus.js'

const { conversations, currentId } = useConversations()
const { status } = useStatus()


const ragClass = computed(() => {
  return status.rag?.enabled ? 'green' : 'gray'
})

const ragText = computed(() => {
  return 'RAG: ' + (status.rag?.enabled ? '已启用' : '未启用')
})

const mcpClass = computed(() => {
  return status.mcp?.length > 0 ? 'green' : 'yellow'
})

const mcpText = computed(() => {
  if (status.mcp?.length > 0) {
    return 'MCP: ' + status.mcp.map(s => s.name).join(', ')
  }
  return 'MCP: 无'
})
</script>
