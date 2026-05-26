import { ref, watch } from 'vue'
import { useConversations } from './useConversations.js'
import { useChat } from './useChat.js'
import * as api from '../api/index.js'

const convStore = {}

export function useReview() {
  const { currentId, currentModel } = useConversations()
  const { messages, pushMessage, saveMessageToBackend } = useChat()

  const currentNoteId = ref(null)
  const currentQuestions = ref([])
  const currentSession = ref(null)
  const isLoading = ref(false)

  const KEY_DEFAULT = 'default'

  function convKey(id) {
    return id ? String(id) : KEY_DEFAULT
  }

  function saveState(key) {
    const k = key !== undefined ? convKey(key) : convKey(currentId.value)
    convStore[k] = {
      currentNoteId: currentNoteId.value,
      currentQuestions: [...currentQuestions.value],
      currentSession: currentSession.value ? { ...currentSession.value } : null,
      isLoading: isLoading.value
    }
  }

  function loadState() {
    const k = convKey(currentId.value)
    const saved = convStore[k]
    if (saved) {
      currentNoteId.value = saved.currentNoteId
      currentQuestions.value = saved.currentQuestions
      currentSession.value = saved.currentSession
      isLoading.value = saved.isLoading
    } else {
      currentNoteId.value = null
      currentQuestions.value = []
      currentSession.value = null
      isLoading.value = false
    }
  }

  loadState()

  watch(currentId, (newId, oldId) => {
    if (oldId !== undefined) saveState(oldId)
    loadState()
  })

  function buildUploadReply(result, sourceLabel) {
    currentNoteId.value = result.noteId
    currentQuestions.value = result.questions || []
    let reply = '✅ 已接收' + sourceLabel
    if (result.noteId) reply += '（ID: ' + result.noteId + '）'
    reply += '，生成 **' + result.questionCount + '** 道复习题：\n\n'
    result.questions.forEach((q, i) => {
      reply += '**' + (i + 1) + '.** ' + q.question + '  _(' + q.type + ')_\n'
    })
    reply += '\n---\n📝 输入 `/review 你的答案` 进行批改\n'
    reply += '   答案格式：每行以题号开头\n   例：\n   1. 监督学习是...\n   2. 过拟合是...'
    return reply
  }

  function buildGradeReply(result) {
    currentSession.value = result
    let reply = '📊 **批改完成！**\n\n'
    reply += '**总分：' + result.totalScore + '/100**\n\n'
    const details = result.details || []
    details.forEach((d, i) => {
      const icon = d.correct ? '✅' : '❌'
      const color = d.correct ? '' : ' ⚠️'
      reply += icon + ' 题' + (i + 1) + '：**' + d.score + '分**' + color + '\n'
      reply += '  💡 ' + (d.feedback || '') + '\n\n'
    })
    reply += '---\n📋 输入 `/list` 查看所有笔记'
    return reply
  }

  function replaceAssistantMsg(content) {
    const msgs = [...messages.value]
    msgs[msgs.length - 1] = { role: 'ASSISTANT', content }
    messages.value = msgs
  }

  function removePlaceholder() {
    messages.value = [...messages.value.slice(0, -1)]
  }

  async function send(role, content) {
    pushMessage(role, content)
    await saveMessageToBackend(role, content)
  }

  async function handleUpload(text) {
    isLoading.value = true
    await send('USER', '/upload ' + text.slice(0, 80) + (text.length > 80 ? '...' : ''))

    pushMessage('ASSISTANT', '...')
    let accumulated = ''

    const success = await api.streamUploadNote(text, currentModel.value, {
      onToken(token) {
        accumulated += token
        const msgs = [...messages.value]
        const last = msgs[msgs.length - 1]
        msgs[msgs.length - 1] = { ...last, content: accumulated }
        messages.value = msgs
      },
      onDone(data) {
        try {
          const result = JSON.parse(data)
          replaceAssistantMsg(buildUploadReply(result, '笔记'))
        } catch (e) {
          replaceAssistantMsg('❌ 解析结果失败：' + e.message)
        }
        isLoading.value = false
      },
      onError(err) {
        replaceAssistantMsg('❌ 流式上传失败：' + err)
        isLoading.value = false
      }
    })

    if (!success) {
      removePlaceholder()
      try {
        const result = await api.uploadNote(text, currentModel.value)
        if (result.success) {
          await send('ASSISTANT', buildUploadReply(result, '笔记'))
        } else {
          await send('ASSISTANT', '❌ 处理失败：' + (result.message || '未知错误'))
        }
      } catch (e) {
        await send('ASSISTANT', '❌ 上传失败：' + e.message)
      }
      isLoading.value = false
    }
  }

  async function handleUploadFile(file) {
    isLoading.value = true
    await send('USER', '/upload (文件: ' + file.name + ')')

    pushMessage('ASSISTANT', '...')
    let accumulated = ''

    const success = await api.streamUploadNoteFile(file, currentModel.value, {
      onToken(token) {
        accumulated += token
        const msgs = [...messages.value]
        const last = msgs[msgs.length - 1]
        msgs[msgs.length - 1] = { ...last, content: accumulated }
        messages.value = msgs
      },
      onDone(data) {
        try {
          const result = JSON.parse(data)
          replaceAssistantMsg(buildUploadReply(result, '文件 **' + file.name + '**'))
        } catch (e) {
          replaceAssistantMsg('❌ 解析结果失败：' + e.message)
        }
        isLoading.value = false
      },
      onError(err) {
        replaceAssistantMsg('❌ 流式上传失败：' + err)
        isLoading.value = false
      }
    })

    if (!success) {
      removePlaceholder()
      try {
        const result = await api.uploadNoteFile(file, currentModel.value)
        if (result.success) {
          await send('ASSISTANT', buildUploadReply(result, '文件 **' + file.name + '**'))
        } else {
          await send('ASSISTANT', '❌ 处理失败：' + (result.message || '未知错误'))
        }
      } catch (e) {
        await send('ASSISTANT', '❌ 上传失败：' + e.message)
      }
      isLoading.value = false
    }
  }

  async function handleGrade(text) {
    if (!currentNoteId.value) {
      await send('ASSISTANT', '⚠️ 请先使用 `/upload` 上传笔记')
      return
    }
    isLoading.value = true
    await send('USER', '/review\n' + text.slice(0, 200) + (text.length > 200 ? '...' : ''))

    const answers = {}
    const lines = text.trim().split('\n')
    let currentIdx = -1
    for (const line of lines) {
      const match = line.match(/^\s*(\d+)[.、．]\s*(.+)/)
      if (match) {
        currentIdx = parseInt(match[1]) - 1
        answers[currentIdx] = match[2].trim()
      } else if (currentIdx >= 0 && line.trim()) {
        answers[currentIdx] += '\n' + line.trim()
      }
    }

    if (Object.keys(answers).length === 0) {
      await send('ASSISTANT', '⚠️ 未识别到答案格式。请每行以题号开头，例如：\n1. 监督学习是...\n2. 过拟合是...')
      isLoading.value = false
      return
    }

    pushMessage('ASSISTANT', '...')
    let accumulated = ''

    const success = await api.streamGradeAnswers(currentNoteId.value, answers, currentModel.value, {
      onToken(token) {
        accumulated += token
        const msgs = [...messages.value]
        const last = msgs[msgs.length - 1]
        msgs[msgs.length - 1] = { ...last, content: accumulated }
        messages.value = msgs
      },
      onDone(data) {
        try {
          const result = JSON.parse(data)
          replaceAssistantMsg(buildGradeReply(result))
        } catch (e) {
          replaceAssistantMsg('❌ 解析批改结果失败：' + e.message)
        }
        isLoading.value = false
      },
      onError(err) {
        replaceAssistantMsg('❌ 流式批改失败：' + err)
        isLoading.value = false
      }
    })

    if (!success) {
      removePlaceholder()
      try {
        const result = await api.gradeAnswers(currentNoteId.value, answers, currentModel.value)
        if (result.success) {
          await send('ASSISTANT', buildGradeReply(result))
        } else {
          await send('ASSISTANT', '❌ 批改失败：' + (result.message || '未知错误'))
        }
      } catch (e) {
        await send('ASSISTANT', '❌ 批改失败：' + e.message)
      }
      isLoading.value = false
    }
  }

  async function handleList() {
    await send('USER', '/list')
    try {
      const notes = await api.listNotes()
      if (!notes || notes.length === 0) {
        await send('ASSISTANT', '📭 暂无笔记，使用 `/upload` 上传')
        return
      }
      let reply = '📚 **学习笔记列表**（共 ' + notes.length + ' 条）\n\n'
      notes.forEach((n, i) => {
        reply += '**' + (i + 1) + '.** ' + n.title + '\n'
        reply += '    分类：' + (n.category || '未分类') + '  |  题目：' + n.questionCount + ' 道\n'
        reply += '    ID：' + n.id + '\n\n'
      })
      reply += '---\n使用 `/upload` 上传新笔记'
      await send('ASSISTANT', reply)
    } catch (e) {
      await send('ASSISTANT', '❌ 查询失败：' + e.message)
    }
  }

  async function handleHelp() {
    await send('USER', '/help')
    let reply = '📖 **复习本命令帮助**\n\n'
    reply += '**`/upload`** `<笔记内容>`\n'
    reply += '  上传学习笔记，自动生成复习题\n\n'
    reply += '**`/upload`** (不带内容)\n'
    reply += '  弹出文件选择器，上传文档文件\n\n'
    reply += '**`/review`** `<你的答案>`\n'
    reply += '  批改答案。生成复习报告。\n\n'
    reply += '**`/select`** `<笔记ID>`\n'
    reply += '  选择已有笔记进行批改（用 `/list` 查看ID）\n\n'
    reply += '**`/list`**\n'
    reply += '  列出所有笔记和复习题\n\n'
    reply += '**`/models`** `[ollama/deepseek]`\n'
    reply += '  查看或切换当前模型\n\n'
    reply += '**`/help`**\n'
    reply += '  显示本帮助\n\n'
    reply += '---\n💡 Tab 键切换 聊天/复习本 模式'
    await send('ASSISTANT', reply)
  }

  async function handleSelect(noteId) {
    await send('USER', '/select ' + noteId)
    try {
      const detail = await api.getNoteDetail(noteId)
      if (!detail || !detail.note) {
        await send('ASSISTANT', '❌ 未找到笔记（ID: ' + noteId + '）')
        return
      }
      currentNoteId.value = detail.note.id
      currentQuestions.value = detail.questions || []
      let reply = '✅ 已选择笔记：**' + detail.note.title + '**\n'
      reply += '共 **' + currentQuestions.value.length + '** 道复习题：\n\n'
      currentQuestions.value.forEach((q, i) => {
        reply += '**' + (i + 1) + '.** ' + q.question + '  _(' + (q.type || '识记型') + ')_\n'
      })
      reply += '\n---\n📝 输入 `/review 你的答案` 进行批改'
      await send('ASSISTANT', reply)
    } catch (e) {
      await send('ASSISTANT', '❌ 选择失败：' + e.message)
    }
  }

  return {
    messages,
    currentNoteId,
    currentQuestions,
    currentSession,
    isLoading,
    handleUpload,
    handleUploadFile,
    handleGrade,
    handleList,
    handleHelp,
    handleSelect
  }
}
