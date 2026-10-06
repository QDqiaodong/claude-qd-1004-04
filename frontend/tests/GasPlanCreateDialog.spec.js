import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import GasPlanCreateDialog from '../src/components/gasplan/GasPlanCreateDialog.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    gasPlanContext: vi.fn(),
    createGasPlan: vi.fn()
  }
}))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function findButton(wrapper, text) {
  return wrapper.findAll('button')
    .map(w => w.element)
    .concat(Array.from(document.body.querySelectorAll('button')))
    .find(b => (b.textContent || '').includes(text))
}

function mountDialog() {
  return mount(GasPlanCreateDialog, {
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  api.gasPlanContext.mockResolvedValue({
    areaDemandM3: 100,
    gateStations: [{ id: 1, stationCode: 'GS-1', stationName: '门站1', inletCapacityM3: 100 }],
    regulatorStations: [{ id: 2, stationCode: 'RS-1', stationName: '调压站1', outletCapacityM3: 80 }]
  })
})

afterEach(() => { document.body.innerHTML = '' })

describe('新建供气计划配额校验', () => {
  it('展示当前片区站点能力，超过上限时给出校验提示且草稿仍可暂存', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    wrapper.vm.form.planNo = 'GP-NEW'
    wrapper.vm.form.operator = '编制人甲'
    wrapper.vm.form.allocations[0].quotaM3 = 120
    wrapper.vm.touched = true
    wrapper.vm.refreshValidation()
    await flushPromises()

    expect(wrapper.text()).toContain('门站1')
    expect(wrapper.text()).toContain('100')
    expect(wrapper.text()).toContain('配额超过进站能力')

    findButton(wrapper, '创建草稿').click()
    await flushPromises()
    expect(api.createGasPlan).toHaveBeenCalledWith(expect.objectContaining({
      planNo: 'GP-NEW',
      allocations: expect.arrayContaining([
        expect.objectContaining({ stationId: 1, quotaM3: 120 })
      ])
    }))
  })

  it('满足能力和需求来源后提交各站配额', async () => {
    api.createGasPlan.mockResolvedValue({ plan: { id: 1 } })
    const wrapper = mountDialog()
    await flushPromises()

    wrapper.vm.form.planNo = 'GP-NEW'
    wrapper.vm.form.operator = '编制人甲'
    wrapper.vm.form.demandSource = 'MANUAL'
    wrapper.vm.form.allocations[0].quotaM3 = 90
    wrapper.vm.form.allocations[1].quotaM3 = 70
    wrapper.vm.touched = true
    wrapper.vm.refreshValidation()
    await flushPromises()

    findButton(wrapper, '创建草稿').click()
    await flushPromises()

    expect(api.createGasPlan).toHaveBeenCalledWith(expect.objectContaining({
      planNo: 'GP-NEW',
      demandSource: 'MANUAL',
      allocations: [
        { stationType: 'GATE_STATION', stationId: 1, quotaM3: 90 },
        { stationType: 'REGULATOR_STATION', stationId: 2, quotaM3: 70 }
      ]
    }))
  })
})
