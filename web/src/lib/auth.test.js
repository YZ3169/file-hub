import { beforeEach, describe, expect, it, vi } from 'vitest'
import { auth, clearSession, currentUserId, getToken, onUnauthorized, setSession } from './auth'

describe('auth', () => {
  beforeEach(() => {
    clearSession({ notify: false })
    auth.ready = false
  })

  it('setSession 同时更新响应式状态和 localStorage', () => {
    setSession({ token: 'tok-1', user: { id: 7, username: 'alice', role: 'USER' } })

    expect(getToken()).toBe('tok-1')
    expect(currentUserId()).toBe(7)
    expect(auth.user.username).toBe('alice')
    expect(localStorage.getItem('filehub.token.v1')).toBe('tok-1')
    expect(JSON.parse(localStorage.getItem('filehub.user.v1'))).toEqual({
      id: 7,
      username: 'alice',
      role: 'USER',
    })
  })

  it('没有用户时 currentUserId 返回 null', () => {
    setSession({ token: 'tok-2', user: null })
    expect(currentUserId()).toBeNull()
  })

  it('clearSession 清理会话并通知订阅者一次', () => {
    const listener = vi.fn()
    const unsubscribe = onUnauthorized(listener)
    setSession({ token: 'tok-3', user: { id: 1, username: 'bob', role: 'USER' } })

    clearSession()

    expect(getToken()).toBeNull()
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('filehub.token.v1')).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)

    // 已经清空后再清空不会重复通知
    clearSession()
    expect(listener).toHaveBeenCalledTimes(1)

    unsubscribe()
  })

  it('notify=false 时不触发 401 回调（主动退出）', () => {
    const listener = vi.fn()
    onUnauthorized(listener)
    setSession({ token: 'tok-4', user: { id: 1, username: 'bob', role: 'USER' } })

    clearSession({ notify: false })

    expect(listener).not.toHaveBeenCalled()
  })

  it('取消订阅后不再收到通知', () => {
    const listener = vi.fn()
    const unsubscribe = onUnauthorized(listener)
    unsubscribe()
    setSession({ token: 'tok-5', user: { id: 1, username: 'bob', role: 'USER' } })

    clearSession()

    expect(listener).not.toHaveBeenCalled()
  })

  it('某个订阅者抛异常不影响其他订阅者', () => {
    const healthy = vi.fn()
    onUnauthorized(() => {
      throw new Error('boom')
    })
    onUnauthorized(healthy)
    setSession({ token: 'tok-6', user: { id: 1, username: 'bob', role: 'USER' } })

    expect(() => clearSession()).not.toThrow()
    expect(healthy).toHaveBeenCalledTimes(1)
  })
})
