import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElPagination, ElSelect } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import GasPlanView from '../src/views/GasPlanView.vue'
import GasPlanDetail from '../src/components/gasplan/GasPlanDetail.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    listGasPlans: vi.fn(),
    gasPlanContext: vi.fn(),
    getGasPlan: vi.fn(),
    getGasPlanHistory: vi.fn(),
    compareGasPlans: vi.fn(),
    validateGasPlan: vi.fn(),
    createGasPlan: vi.fn(),
    updateGasPlan: vi.fn(),
    gasPlanAction: vi.fn(),
    copyGasPlan: vi.fn()
  }
}))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const page = (items, total = items.length) => ({ items, total, page: 0, size: 10, totalPages: 1 })

function mountView() {
  return mount(GasPlanView, {
    props: { tenant: 'A片区' },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listGasPlans.mockResolvedValue(page([]))
  api.gasPlanContext.mockResolvedValue({ gateStations: [], regulatorStations: [], areaDemandM3: 0 })
  api.getGasPlanHistory.mockResolvedValue({ versions: [], histories: [] })
  api.getGasPlan.mockResolvedValue({ plan: { id: 1 }, quotas: [], histories: [], validation: { errors: [], warnings: [] } })
})

afterEach(() => { document.body.innerHTML = '' })

describe('供气计划列表筛选', () => {
  it('按日期和状态查询，切换片区时回到第一页', async () => {
    const wrapper = mountView()
    await flushPromises()

    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    picker.vm.$emit('update:modelValue', '2026-10-06')
    wrapper.vm.search()
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ planDate: '2026-10-06', page: 0 }))

    const statusSelect = wrapper.findComponent(ElSelect)
    statusSelect.vm.$emit('update:modelValue', '已冻结')
    statusSelect.vm.$emit('change', '已冻结')
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ planDate: '2026-10-06', status: '已冻结', page: 0 }))

    wrapper.findComponent(ElPagination).vm.$emit('current-change', 3)
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2 }))

    await wrapper.setProps({ tenant: 'B片区' })
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0 }))
  })

  it('打开详情时拉取配额和版本组件', async () => {
    api.listGasPlans.mockResolvedValue(page([
      { id: 8, planNo: 'GP-1', planVersion: 1, planDate: '2026-10-06', status: '已冻结', demandSource: 'MANUAL' }
    ], 1))
    const wrapper = mountView()
    await flushPromises()

    const button = Array.from(wrapper.element.querySelectorAll('button'))
      .find(b => b.textContent.includes('配额/版本'))
    expect(button).toBeTruthy()
    button.click()
    await flushPromises()
    expect(api.getGasPlan).toHaveBeenCalledWith(8)
    expect(api.getGasPlanHistory).toHaveBeenCalledWith(8)
    expect(wrapper.findComponent(GasPlanDetail).exists()).toBe(true)
  })
})
