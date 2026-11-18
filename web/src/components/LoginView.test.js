import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', () => ({
  login: vi.fn(),
  register: vi.fn(),
}))
vi.mock('../lib/auth', () => ({
  setSession: vi.fn(),
}))
vi.mock('../stores/uploader', () => ({
  startWorkspace: vi.fn(() => Promise.resolve()),
}))

import { login, register } from '../lib/api'
import { setSession } from '../lib/auth'
import { startWorkspace } from '../stores/uploader'
import LoginView from './LoginView.vue'

function fill(wrapper, username, password) {
  wrapper.find('[data-testid="auth-username"]').setValue(username)
  wrapper.find('[data-testid="auth-password"]').setValue(password)
}

describe('LoginView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('用户名为空时提交按钮禁用', () => {
    const wrapper = mount(LoginView)
    expect(wrapper.find('[data-testid="auth-submit"]').attributes('disabled')).toBeDefined()
  })

  it('登录成功后保存会话并启动工作区', async () => {
    login.mockResolvedValue({ token: 'tok', user: { id: 1, username: 'alice' } })
    const wrapper = mount(LoginView)

    await fill(wrapper, 'alice', 'secret')
    expect(wrapper.find('[data-testid="auth-submit"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledWith('alice', 'secret')
    expect(setSession).toHaveBeenCalledWith({
      token: 'tok',
      user: { id: 1, username: 'alice' },
    })
    expect(startWorkspace).toHaveBeenCalledTimes(1)
  })

  it('限流时给出稍后再试的提示', async () => {
    login.mockRejectedValue(Object.assign(new Error('too many'), { status: 429 }))
    const wrapper = mount(LoginView)

    await fill(wrapper, 'alice', 'secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-testid="auth-error"]').text()).toContain('尝试过于频繁')
  })

  it('账号密码错误时提示，不暴露具体原因', async () => {
    login.mockRejectedValue(Object.assign(new Error('bad credentials'), { status: 401 }))
    const wrapper = mount(LoginView)

    await fill(wrapper, 'alice', 'wrong')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-testid="auth-error"]').text()).toContain('账号或密码错误')
  })

  it('服务端未连接时给出启动提示', async () => {
    login.mockRejectedValue(Object.assign(new Error('offline'), { status: 0 }))
    const wrapper = mount(LoginView)

    await fill(wrapper, 'alice', 'secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-testid="auth-error"]').text()).toContain('连不上服务端')
  })

  it('注册模式要求密码至少 8 位', async () => {
    register.mockResolvedValue({ token: 'tok', user: { id: 2, username: 'newbie' } })
    const wrapper = mount(LoginView)

    await wrapper.find('[data-testid="auth-register-tab"]').trigger('click')
    await fill(wrapper, 'newbie', '1234567')

    expect(wrapper.find('[data-testid="auth-submit"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-testid="auth-password"]').setValue('12345678')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(register).toHaveBeenCalledWith('newbie', '12345678')
  })
})
