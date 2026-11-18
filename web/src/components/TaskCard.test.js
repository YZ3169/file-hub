import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskCard from './TaskCard.vue'

function task(overrides = {}) {
  return {
    id: 't-1',
    name: 'demo.bin',
    size: 100,
    completedBytes: 50,
    state: 'uploading',
    message: '已上传 1/2 片',
    hash: '0123456789abcdef0123456789abcdef',
    hashProgress: 0,
    chunkSize: 50,
    chunkTotal: 2,
    chunkStates: ['done', 'pending'],
    uploadedChunks: 1,
    skippedChunks: 0,
    retryTotal: 0,
    healRounds: 0,
    speed: 0,
    eta: 0,
    ...overrides,
  }
}

describe('TaskCard', () => {
  it('上传中按已传字节显示进度和状态', () => {
    const wrapper = mount(TaskCard, { props: { task: task() } })
    const bar = wrapper.find('[role="progressbar"]')

    expect(bar.attributes('aria-valuenow')).toBe('50')
    expect(bar.attributes('aria-label')).toContain('上传中')
    expect(wrapper.text()).toContain('已上传 1/2 片')
    expect(wrapper.text()).toContain('1/2 片')
    expect(wrapper.find('button').exists()).toBe(true)
  })

  it('哈希阶段用 hashProgress 计算进度', () => {
    const wrapper = mount(TaskCard, {
      props: { task: task({ state: 'hashing', hashProgress: 0.42, message: '' }) },
    })

    expect(wrapper.find('[role="progressbar"]').attributes('aria-valuenow')).toBe('42')
    expect(wrapper.text()).toContain('计算哈希')
  })

  it('完成后进度为 100，并显示校验通过', () => {
    const wrapper = mount(TaskCard, {
      props: {
        task: task({
          state: 'done',
          completedBytes: 100,
          message: '上传完成：SHA-256 通过',
        }),
      },
    })

    expect(wrapper.find('[role="progressbar"]').attributes('aria-valuenow')).toBe('100')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('SHA-256 校验通过')
    expect(wrapper.find('.ring.spinning').exists()).toBe(false)
  })

  it('失败任务显示错误文案和重试按钮', () => {
    const wrapper = mount(TaskCard, {
      props: {
        task: task({ state: 'error', message: '分片 2 上传失败：网络错误' }),
      },
    })

    expect(wrapper.find('.task-msg').classes()).toContain('error')
    expect(wrapper.text()).toContain('网络错误')
    expect(wrapper.text()).toContain('重试')
  })

  it('秒传命中显示 100% 和秒传徽标', () => {
    const wrapper = mount(TaskCard, {
      props: {
        task: task({ state: 'instant', instant: true, completedBytes: 100, message: '' }),
      },
    })

    expect(wrapper.find('[role="progressbar"]').attributes('aria-valuenow')).toBe('100')
    expect(wrapper.text()).toContain('秒传命中')
    expect(wrapper.text()).toContain('秒传')
  })
})
