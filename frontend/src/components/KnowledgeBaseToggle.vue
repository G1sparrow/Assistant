<template>
  <div class="toolbar-btn" :class="{ uploading: uploading }" @click="triggerUpload" title="上传文档到知识库">
    <span class="toolbar-icon">{{ uploading ? '⏳' : '📚' }}</span>
    <span class="toolbar-label">{{ uploading ? '上传中...' : '上传知识库' }}</span>
    <input
      ref="fileInput"
      type="file"
      accept=".txt,.md,.json,.xml"
      style="display:none"
      @change="handleFile"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { uploadDocument } from '../api/index.js'

const emit = defineEmits(['uploaded'])
const fileInput = ref(null)
const uploading = ref(false)

function triggerUpload() {
  if (!uploading.value) {
    fileInput.value?.click()
  }
}

async function handleFile(e) {
  const file = e.target.files[0]
  if (!file) return
  e.target.value = ''

  uploading.value = true
  try {
    const result = await uploadDocument(file)
    if (result.success) {
      showToast(result.message || '文档上传成功', 'success')
      emit('uploaded')
    } else {
      showToast(result.message || '上传失败', 'error')
    }
  } catch (err) {
    showToast('上传失败: ' + err.message, 'error')
  } finally {
    uploading.value = false
  }
}

function showToast(msg, type) {
  const bg = type === 'success' ? '#22c55e' : '#ef4444'
  const el = document.createElement('div')
  el.id = 'toast'
  el.textContent = msg
  el.style.background = bg
  document.body.appendChild(el)
  setTimeout(() => {
    el.style.opacity = '0'
    setTimeout(() => el.remove(), 250)
  }, 3000)
}
</script>
