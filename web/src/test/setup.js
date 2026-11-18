import { afterEach, beforeEach } from 'vitest'

// 每个用例都从干净的 localStorage 开始：认证、断点续传记录都按用户存在这里，
// 用例之间互相污染会导致「上个用例的 token 泄漏到下个用例」。
beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  localStorage.clear()
})
