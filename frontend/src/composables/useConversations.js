import { ref, computed } from 'vue'
import * as api from '../api/index.js'

const conversations = ref([])
const currentId = ref(null)

export function useConversations() {
  async function load() {
    try {
      conversations.value = await api.getConversations()
    } catch (e) {
      console.error('加载对话列表失败', e)
    }
  }

  async function create(modelType) {
    try {
      const data = await api.createConversation(modelType)
      const conv = { id: data.conversationId, title: '新对话', updatedAt: new Date().toISOString(), modelType: modelType || 'ollama' }
      conversations.value.unshift(conv)
      currentId.value = data.conversationId
      return data.conversationId
    } catch (e) {
      console.error('创建对话失败', e)
      return null
    }
  }

  function switchTo(id) {
    currentId.value = id
  }

  async function remove(id) {
    try {
      await api.deleteConversation(id)
      conversations.value = conversations.value.filter(c => c.id !== id)
      if (currentId.value === id) {
        currentId.value = conversations.value[0]?.id || null
      }
    } catch (e) {
      console.error('删除对话失败', e)
    }
  }

  async function updateModel(id, modelType) {
    try {
      await api.updateConversationModel(id, modelType)
      const conv = conversations.value.find(c => c.id === id)
      if (conv) conv.modelType = modelType
    } catch (e) {
      console.error('更新模型失败', e)
    }
  }

  const currentModel = computed(() => {
    const conv = conversations.value.find(c => c.id === currentId.value)
    return conv?.modelType || 'ollama'
  })

  function formatTime(dateStr) {
    if (!dateStr) return ''
    const date = new Date(dateStr)
    const now = new Date()
    const diff = now - date
    if (diff < 86400000 && date.getDate() === now.getDate()) {
      return String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0')
    }
    const yesterday = new Date(now)
    yesterday.setDate(yesterday.getDate() - 1)
    if (date.getDate() === yesterday.getDate() && date.getMonth() === yesterday.getMonth()) {
      return '昨天'
    }
    return (date.getMonth() + 1) + '/' + date.getDate()
  }

  function updateTitle(id, title) {
    const c = conversations.value.find(c => c.id === id)
    if (c && c.title === '新对话') {
      c.title = title.length > 20 ? title.slice(0, 20) + '...' : title
    }
  }

  return {
    conversations,
    currentId,
    currentModel,
    load,
    create,
    switchTo,
    remove,
    updateModel,
    formatTime,
    updateTitle
  }
}
