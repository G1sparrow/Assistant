<template>
  <LearningLibrary v-if="currentPage === 'library'" />
  <template v-else>
    <div class="app-layout">
      <Sidebar />
      <main class="main-content">
        <ChatHeader />
        <ChatMessages />
        <MessageInput />
      </main>
    </div>
    <div id="toast"></div>
  </template>
</template>

<script setup>
import { onMounted } from 'vue'
import Sidebar from './components/Sidebar.vue'
import ChatHeader from './components/ChatHeader.vue'
import ChatMessages from './components/ChatMessages.vue'
import MessageInput from './components/MessageInput.vue'
import LearningLibrary from './components/library/LearningLibrary.vue'
import { useConversations } from './composables/useConversations.js'
import { useStatus } from './composables/useStatus.js'
import { useMode } from './composables/useMode.js'

const { load } = useConversations()
const { startPolling } = useStatus()
const { currentPage } = useMode()

onMounted(async () => {
  await load()
  startPolling()
})
</script>
