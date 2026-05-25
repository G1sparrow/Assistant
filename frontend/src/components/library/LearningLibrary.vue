<template>
  <div class="ll-page">
    <div class="ll-topbar">
      <h1>学习资料库</h1>
      <span class="ll-subtitle">笔记 · 复习题 · 答题记录</span>
      <button class="ll-back-btn" @click="closeLibrary">← 返回</button>
    </div>
    <div class="ll-body">
      <!-- Left Panel -->
      <div class="ll-left">
        <div class="ll-search">
          <span>🔍</span>
          <input type="text" v-model="searchQuery" placeholder="搜索文件名...">
        </div>
        <div class="ll-tabs">
          <button class="ll-tab" :class="{ active: activeTab === 'notes' }" @click="activeTab = 'notes'">
            📝 笔记
            <span class="ll-tab-count">{{ notes.length }}</span>
          </button>
          <button class="ll-tab" :class="{ active: activeTab === 'questions' }" @click="activeTab = 'questions'">
            📋 复习题
            <span class="ll-tab-count">{{ totalQuestionCount }}</span>
          </button>
          <button class="ll-tab" :class="{ active: activeTab === 'sessions' }" @click="activeTab = 'sessions'">
            ✍️ 答题记录
            <span class="ll-tab-count">{{ totalSessionCount }}</span>
          </button>
        </div>
        <div class="ll-file-list">
          <div v-if="filteredNotes.length === 0" class="ll-empty">暂无内容</div>
          <div v-for="note in filteredNotes" :key="note.id" class="ll-file-item"
               :class="{ selected: selectedNoteId === note.id }"
               @click="selectNote(note.id)">
            <div class="ll-file-name">{{ note.title || '无标题' }}</div>
            <div class="ll-file-meta">
              <span>{{ formatDate(note.createdAt) }}</span>
              <span class="ll-file-badge">{{ note.category || '笔记' }}</span>
              <span v-if="note.questionCount" class="ll-file-badge ll-q-badge">{{ note.questionCount }}题</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Right Panel -->
      <div class="ll-right">
        <div v-if="!selectedNoteId" class="ll-placeholder">
          <div class="ll-placeholder-icon">📄</div>
          <p>从左侧选择一个文件查看详情</p>
        </div>
        <template v-else>
          <div class="ll-file-header">
            <span class="ll-file-title">{{ selectedNote?.title || '无标题' }}</span>
            <div class="ll-actions">
              <button class="ll-btn" @click="editMode = false" :class="{ active: !editMode }">👁 预览</button>
              <button class="ll-btn" @click="editMode = true" :class="{ active: editMode }">✏️ 编辑</button>
              <button class="ll-btn ll-btn-danger" @click="handleDelete">🗑 删除</button>
            </div>
          </div>
          <div class="ll-content">
            <template v-if="activeTab === 'notes'">
              <div v-if="editMode" class="ll-editor">
                <textarea v-model="editContent" class="ll-textarea" @input="updateCharCount"></textarea>
              </div>
              <div v-else class="ll-preview" v-html="renderPreview(noteContent)"></div>
            </template>
            <template v-else-if="activeTab === 'questions'">
              <div v-if="loadingDetail" class="ll-loading">加载中...</div>
              <div v-else-if="!currentQuestions.length" class="ll-empty">暂无复习题</div>
              <ul v-else class="ll-question-list">
                <li v-for="(q, i) in currentQuestions" :key="i" class="ll-question-item">
                  <span class="ll-q-num">{{ i + 1 }}.</span>
                  <span class="ll-q-text">{{ q.question }}</span>
                  <span class="ll-q-type">{{ q.type || '识记型' }}</span>
                </li>
              </ul>
            </template>
            <template v-else-if="activeTab === 'sessions'">
              <div v-if="loadingDetail" class="ll-loading">加载中...</div>
              <div v-else-if="!currentSessions.length" class="ll-empty">暂无答题记录</div>
              <div v-else class="ll-session-list">
                <div v-for="s in currentSessions" :key="s.id" class="ll-session-item">
                  <div class="ll-session-header">
                    <span>{{ formatDate(s.createdAt) }}</span>
                    <span class="ll-score" :class="scoreClass(s.totalScore)">{{ s.totalScore }}分</span>
                  </div>
                  <div class="ll-score-bar">
                    <div class="ll-score-fill" :class="scoreClass(s.totalScore)"
                         :style="{ width: s.totalScore + '%' }"></div>
                  </div>
                </div>
              </div>
            </template>
          </div>
          <div class="ll-status">
            <span>{{ statusText }}</span>
            <span>{{ charCount }} 字符</span>
            <span>{{ savedStatus }}</span>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import * as api from '../../api/index.js'
import { useMode } from '../../composables/useMode.js'

const { closeLibrary } = useMode()

const notes = ref([])
const detailCache = ref({})
const loading = ref(true)
const loadingDetail = ref(false)
const searchQuery = ref('')
const activeTab = ref('notes')
const selectedNoteId = ref(null)
const editMode = ref(false)
const editContent = ref('')
const charCount = ref(0)
const savedStatus = ref('')

const filteredNotes = computed(() => {
  let result = notes.value
  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim().toLowerCase()
    result = result.filter(n => (n.title || '').toLowerCase().includes(q))
  }
  if (activeTab.value === 'questions') {
    result = result.filter(n => n.questionCount > 0)
  }
  if (activeTab.value === 'sessions') {
    result = result.filter(n => noteSessions(n.id).length > 0)
  }
  return result
})

const totalQuestionCount = computed(() =>
  notes.value.reduce((s, n) => s + (n.questionCount || 0), 0)
)

const totalSessionCount = computed(() => {
  let c = 0
  for (const d of Object.values(detailCache.value)) c += (d.sessions || []).length
  return c
})

const selectedNote = computed(() =>
  notes.value.find(n => n.id === selectedNoteId.value)
)

const currentQuestions = computed(() =>
  detailCache.value[selectedNoteId.value]?.questions || []
)

const currentSessions = computed(() =>
  detailCache.value[selectedNoteId.value]?.sessions || []
)

const noteContent = computed(() => {
  const cached = detailCache.value[selectedNoteId.value]
  if (cached?.note?.content) return cached.note.content
  return notes.value.find(n => n.id === selectedNoteId.value)?.content || ''
})

const statusText = computed(() => {
  if (!selectedNote.value) return ''
  return selectedNote.value.category ? '分类: ' + selectedNote.value.category : ''
})

function noteSessions(noteId) {
  return detailCache.value[noteId]?.sessions || []
}

function selectNote(noteId) {
  selectedNoteId.value = noteId
  editMode.value = false
  if (!detailCache.value[noteId]) {
    loadDetail(noteId)
  }
  const cached = detailCache.value[noteId]
  editContent.value = cached?.note?.content || notes.value.find(n => n.id === noteId)?.content || ''
  charCount.value = editContent.value.length
  savedStatus.value = ''
}

async function loadDetail(noteId) {
  loadingDetail.value = true
  try {
    const data = await api.getNoteDetail(noteId)
    detailCache.value[noteId] = data
  } catch {
    // silent
  }
  loadingDetail.value = false
}

function updateCharCount() {
  charCount.value = editContent.value.length
  savedStatus.value = '⚠️ 未保存'
}

function renderPreview(text) {
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.+?)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br>')
}

async function handleDelete() {
  if (!selectedNoteId.value) return
  if (!confirm('确定要删除该笔记及其所有复习题吗？')) return
  try {
    const res = await fetch('/api/review/notes/' + selectedNoteId.value, { method: 'DELETE' })
    if (res.ok) {
      notes.value = notes.value.filter(n => n.id !== selectedNoteId.value)
      delete detailCache.value[selectedNoteId.value]
      selectedNoteId.value = null
    }
  } catch {
    // silent
  }
}

function formatDate(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.getFullYear() + '-' + (d.getMonth() + 1).toString().padStart(2, '0') + '-' + d.getDate().toString().padStart(2, '0')
}

function scoreClass(score) {
  if (score >= 70) return 'sc-high'
  if (score >= 40) return 'sc-mid'
  return 'sc-low'
}

onMounted(async () => {
  try {
    notes.value = await api.listNotes()
  } catch {
    // silent
  }
  loading.value = false
})

watch(activeTab, () => {
  selectedNoteId.value = null
  editMode.value = false
})

watch(() => detailCache.value[selectedNoteId.value]?.note?.content, (newContent) => {
  if (newContent && !editMode.value) {
    editContent.value = newContent
    charCount.value = newContent.length
  }
})
</script>
