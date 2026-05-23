<template>
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

<script setup>
import { onMounted } from 'vue'
import Sidebar from './components/Sidebar.vue'
import ChatHeader from './components/ChatHeader.vue'
import ChatMessages from './components/ChatMessages.vue'
import MessageInput from './components/MessageInput.vue'
import { useConversations } from './composables/useConversations.js'
import { useStatus } from './composables/useStatus.js'

const { load } = useConversations()
const { startPolling } = useStatus()

onMounted(async () => {
  await load()
  startPolling()
})
</script>
