<template>
  <details class="tool-call-card">
    <summary class="tool-call-summary">
      <span class="tool-call-icon">🔧</span>
      使用工具: <span class="tool-call-name">{{ toolCall.name }}</span>
    </summary>
    <div class="tool-call-content">
      <div class="tool-call-section" v-if="toolCall.arguments">
        <div class="tool-call-section-label">输入</div>
        <pre>{{ toolCall.arguments }}</pre>
      </div>
      <div class="tool-call-section" v-if="toolCall.result">
        <div class="tool-call-section-label">输出</div>
        <pre>{{ toolCall.result }}</pre>
      </div>
    </div>
  </details>
</template>

<script setup>
defineProps({
  toolCall: { type: Object, required: true }
})
</script>
