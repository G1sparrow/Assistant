const BASE = '/api'

async function request(url, options = {}) {
  const res = await fetch(BASE + url, options)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

export function getConversations() {
  return request('/conversations')
}

export function createConversation(modelType = 'ollama') {
  return request('/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'modelType=' + encodeURIComponent(modelType)
  })
}

export function getMessages(conversationId) {
  return request(`/conversations/${conversationId}/messages`)
}

export function deleteConversation(conversationId) {
  return request(`/conversations/${conversationId}`, { method: 'DELETE' })
}

export function updateConversationModel(conversationId, modelType) {
  const params = new URLSearchParams({ modelType })
  return request(`/conversations/${conversationId}/model`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: params.toString()
  })
}

export function sendMessage(conversationId, text) {
  const params = new URLSearchParams({ conversationId, message: text })
  return request('/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: params.toString()
  })
}

export function getStatus() {
  return request('/status')
}

export async function uploadDocument(file) {
  const formData = new FormData()
  formData.append('file', file)
  const res = await fetch(BASE + '/documents/upload', {
    method: 'POST',
    body: formData
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

// ============ Review ============

export async function uploadNote(content, modelType) {
  const params = new URLSearchParams({ content, model: modelType || 'ollama' })
  const res = await fetch(BASE + '/review/upload', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: params.toString()
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function uploadNoteFile(file, modelType) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('model', modelType || 'ollama')
  const res = await fetch(BASE + '/review/upload', {
    method: 'POST',
    body: formData
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function gradeAnswers(noteId, answers, modelType) {
  const res = await fetch(BASE + '/review/grade?noteId=' + noteId + '&model=' + (modelType || 'ollama'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ answers })
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export function listNotes() {
  return request('/review/notes')
}

export function getNoteDetail(noteId) {
  return request(`/review/notes/${noteId}`)
}

export function saveMessage(conversationId, role, content) {
  return request(`/review/${conversationId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role, content })
  })
}

export async function streamChat(conversationId, text, callbacks) {
  const { onToken, onToolCall, onDone, onError } = callbacks
  try {
    const res = await fetch(BASE + '/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'conversationId=' + encodeURIComponent(conversationId) +
            '&message=' + encodeURIComponent(text)
    })
    if (!res.ok) return false
    if (!res.body) return false

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''

      for (const part of parts) {
        const lines = part.split('\n')
        let eventType = ''
        let data = ''
        for (const line of lines) {
          if (line.startsWith('event:')) eventType = line.slice(6)
          else if (line.startsWith('data:')) data = line.slice(5)
        }
        if (!eventType && !data) continue

        switch (eventType) {
          case 'token':
            if (onToken) onToken(data)
            break
          case 'tool_call':
            if (onToolCall) onToolCall(data)
            break
          case 'done':
            if (onDone) onDone(data)
            return true
          case 'error':
            if (onError) onError(data)
            return false
        }
      }
    }
    return true
  } catch {
    return false
  }
}

export async function streamGradeAnswers(noteId, answers, modelType, callbacks) {
  const { onToken, onDone, onError } = callbacks
  try {
    const res = await fetch(BASE + '/review/grade/stream?noteId=' + noteId + '&model=' + (modelType || 'ollama'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answers })
    })
    if (!res.ok) return false
    if (!res.body) return false

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''

      for (const part of parts) {
        const lines = part.split('\n')
        let eventType = ''
        let data = ''
        for (const line of lines) {
          if (line.startsWith('event:')) eventType = line.slice(6)
          else if (line.startsWith('data:')) data = line.slice(5)
        }
        if (!eventType && !data) continue

        switch (eventType) {
          case 'token':
            if (onToken) onToken(data)
            break
          case 'done':
            if (onDone) onDone(data)
            return true
          case 'error':
            if (onError) onError(data)
            return false
        }
      }
    }
    return true
  } catch {
    return false
  }
}

export async function streamUploadNote(content, modelType, callbacks) {
  const { onToken, onDone, onError } = callbacks
  try {
    const params = new URLSearchParams({ content, model: modelType || 'ollama' })
    const res = await fetch(BASE + '/review/upload/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
    if (!res.ok) return false
    if (!res.body) return false

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''

      for (const part of parts) {
        const lines = part.split('\n')
        let eventType = ''
        let data = ''
        for (const line of lines) {
          if (line.startsWith('event:')) eventType = line.slice(6)
          else if (line.startsWith('data:')) data = line.slice(5)
        }
        if (!eventType && !data) continue

        switch (eventType) {
          case 'token':
            if (onToken) onToken(data)
            break
          case 'done':
            if (onDone) onDone(data)
            return true
          case 'error':
            if (onError) onError(data)
            return false
        }
      }
    }
    return true
  } catch {
    return false
  }
}

export async function streamUploadNoteFile(file, modelType, callbacks) {
  const { onToken, onDone, onError } = callbacks
  try {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('model', modelType || 'ollama')
    const res = await fetch(BASE + '/review/upload/stream', {
      method: 'POST',
      body: formData
    })
    if (!res.ok) return false
    if (!res.body) return false

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''

      for (const part of parts) {
        const lines = part.split('\n')
        let eventType = ''
        let data = ''
        for (const line of lines) {
          if (line.startsWith('event:')) eventType = line.slice(6)
          else if (line.startsWith('data:')) data = line.slice(5)
        }
        if (!eventType && !data) continue

        switch (eventType) {
          case 'token':
            if (onToken) onToken(data)
            break
          case 'done':
            if (onDone) onDone(data)
            return true
          case 'error':
            if (onError) onError(data)
            return false
        }
      }
    }
    return true
  } catch {
    return false
  }
}
