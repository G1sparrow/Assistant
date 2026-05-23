import { reactive, onUnmounted } from 'vue'
import * as api from '../api/index.js'

const status = reactive({
  conversations: 0,
  model: '',
  rag: null,
  mcp: [],
  online: false
})

let timer = null

export function useStatus() {
  async function fetch() {
    try {
      const data = await api.getStatus()
      status.conversations = data.conversations || 0
      status.model = data.model || ''
      status.rag = data.rag || null
      status.mcp = data.mcp || []
      status.online = true
    } catch {
      status.online = false
    }
  }

  function startPolling() {
    fetch()
    timer = setInterval(fetch, 30000)
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  onUnmounted(stopPolling)

  return { status, fetch, startPolling, stopPolling }
}
