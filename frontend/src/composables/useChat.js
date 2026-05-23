import { ref } from 'vue'
import * as api from '../api/index.js'
import { useConversations } from './useConversations.js'

const messages = ref([])
const isLoading = ref(false)

export function useChat() {
  const { create, updateTitle } = useConversations()

  async function loadMessages(conversationId) {
    try {
      messages.value = await api.getMessages(conversationId)
    } catch (e) {
      console.error('加载消息失败', e)
    }
  }

  function clear() {
    messages.value = []
  }

  function pushMessage(role, content) {
    messages.value.push({ role, content })
  }

  async function send(conversationId, text) {
    if (!text.trim()) return
    let cid = conversationId
    if (!cid) {
      cid = await create()
      if (!cid) return
    }

    pushMessage('USER', text)
    isLoading.value = true

    let fullText = ''
    let toolCalls = []

    const success = await api.streamChat(cid, text, {
      onToken(token) {
        fullText += token
        if (fullText === token) {
          pushMessage('ASSISTANT', '')
        }
        messages.value[messages.value.length - 1].content = fullText
        // force reactivity
        messages.value = [...messages.value]
      },
      onToolCall(data) {
        try {
          const info = JSON.parse(data)
          toolCalls.push(info)
          const last = messages.value[messages.value.length - 1]
          if (last && last.toolCalls) {
            last.toolCalls = [...last.toolCalls, info]
          } else if (last) {
            last.toolCalls = [info]
          }
          messages.value = [...messages.value]
        } catch (e) {}
      },
      onDone() {
        updateTitle(cid, text)
        isLoading.value = false
      },
      onError(err) {
        pushMessage('ASSISTANT', '抱歉，发生错误: ' + err)
        isLoading.value = false
      }
    })

    if (!success) {
      // fallback to non-streaming
      try {
        const res = await api.sendMessage(cid, text)
        pushMessage('ASSISTANT', res.message)
        if (res.error) console.error('AI 回复错误')
        updateTitle(cid, text)
      } catch (e) {
        pushMessage('ASSISTANT', '抱歉，连接出现问题，请检查服务是否正常运行。')
      }
      isLoading.value = false
    }

    return cid
  }

  return {
    messages,
    isLoading,
    loadMessages,
    clear,
    pushMessage,
    send
  }
}
