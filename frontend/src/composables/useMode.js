import { ref } from 'vue'

const currentMode = ref('chat')
const currentPage = ref('messages')

export function useMode() {
  function toggle() {
    currentMode.value = currentMode.value === 'chat' ? 'review' : 'chat'
  }

  function switchTo(mode) {
    currentMode.value = mode
  }

  function openLibrary() {
    currentPage.value = 'library'
  }

  function closeLibrary() {
    currentPage.value = 'messages'
  }

  return { currentMode, currentPage, toggle, switchTo, openLibrary, closeLibrary }
}
