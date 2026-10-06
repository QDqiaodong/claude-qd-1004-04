import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import GasPlanDetail from '../src/components/gasplan/GasPlanDetail.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    gasPlanContext: vi.fn(),
    getGasPlan: vi.fn(),
    getGasPlanHistory: vi.fn(),
    compareGasPlans: vi.fn(),
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

const gate = { id: 1, stationCode: 'GS-1', stationName: '门站1', inletCapacityM3: 100, dailyFlowM3: 90 }
const regulator = { id: 2, stationCode: 'RS-1', stationName: '调压站1', outletCapacityM3: 80 }

function detailData(overrides = {}) {
  return {
    plan: {
      id: 10, planNo: 'GP-1', planVersion: 1, planDate: '2026-10-06',
      status: '草稿', areaDemandM3: 100, demandSource: 'METER_SNAPSHOT',
      demandReference: '抄表', changeNote: '初版', reviewOpinion: null,
      submittedBy: null, submittedAt: null, reviewedBy: null, reviewedAt: null,
      frozenBy: null, frozenAt: null, sourceVersionId: null,
      ...(overrides.plan || {})
    },
    quotas: overrides.quotas || [
      { id: 1, stationType: 'GATE_STATION', stationId: 1, stationCode: 'GS-1', stationName: '门站1', capacityM3: 100, dailyFlowM3: 90, quotaM3: 60, negative: false, overCapacity: false },
      { id: 2, stationType: 'REGULATOR_STATION', stationId: 2, stationCode: 'RS-1', stationName: '调压站1', capacityM3: 80, dailyFlowM3: null, quotaM3: 50, negative: false, overCapacity: false }
    ],
    histories: overrides.histories || [],
    validation: overrides.validation || {
      validForSubmit: true, errors: [], warnings: [], gateTotalQuotaM3: 60, gateTotalCapacityM3: 100,
      regulatorTotalQuotaM3: 50, regulatorTotalCapacityM3: 80, gateTotalOverCapacity: false,
      missingDemandSource: false, hasNegativeQuota: false, missingStation: false
    }
  }
}

function mountDetail() {
  return mount(GasPlanDetail, {
    props: { versionId: 10 },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
  api.gasPlanContext.mockResolvedValue({ gateStations: [gate], regulatorStations: [regulator], areaDemandM3: 100 })
  api.getGasPlanHistory.mockResolvedValue({
    versions: [
      { id: 10, planVersion: 1, status: '已冻结' },
      { id: 11, planVersion: 2, status: '草稿' }
    ],
    histories: [
      { id: 1, action: '冻结', fromStatus: '已提交', toStatus: '已冻结', operatorName: '调度甲', note: '冻结留存', createdAt: 't2' },
      { id: 2, action: '复核通过', fromStatus: '待复核', toStatus: '已提交', operatorName: '复核甲', note: '同意', createdAt: 't1' }
    ]
  })
})

afterEach(() => { document.body.innerHTML = '' })

describe('配额校验与提交冲突', () => {
  it('配额超过门站进站能力时展示错误并禁用提交', async () => {
    api.getGasPlan.mockResolvedValue(detailData({
      quotas: [
        { stationType: 'GATE_STATION', stationId: 1, stationCode: 'GS-1', stationName: '门站1', capacityM3: 100, quotaM3: 120 },
        { stationType: 'REGULATOR_STATION', stationId: 2, stationCode: 'RS-1', stationName: '调压站1', capacityM3: 80, quotaM3: 50 }
      ]
    }))
    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.text()).toContain('配额超过进站能力')
    const submit = Array.from(wrapper.element.querySelectorAll('button'))
      .find(b => (b.textContent || '').includes('提交复核'))
    expect(submit.getAttribute('disabled')).toBeDefined()
  })

  it('并发复核冲突时展示 409 反馈且不改写页面配额', async () => {
    api.getGasPlan.mockResolvedValue(detailData({
      plan: { status: '待复核', areaDemandM3: 100, demandSource: 'MANUAL' }
    }))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '复核意见' })
    api.gasPlanAction.mockRejectedValue(new Error('该计划正由其他操作员复核或提交，请刷新后重试；您的配额、意见和状态均未改写'))

    const wrapper = mountDetail()
    await flushPromises()
    Array.from(wrapper.element.querySelectorAll('button'))
      .find(b => (b.textContent || '').includes('复核通过')).click()
    await flushPromises()

    expect(api.gasPlanAction).toHaveBeenCalledWith(10, 'approve',
      expect.objectContaining({ operator: '复核意见', opinion: '复核意见' }))
    expect(wrapper.text()).toContain('其他操作员')
    expect(wrapper.text()).toContain('门站1')
    expect(api.updateGasPlan).not.toHaveBeenCalled()
  })
})

describe('冻结历史、复制与版本差异', () => {
  it('已冻结版本展示历史意见，只能复制生成新版本', async () => {
    api.getGasPlan.mockResolvedValue(detailData({
      plan: {
        status: '已冻结', reviewedBy: '复核甲', reviewOpinion: '同意',
        frozenBy: '调度甲', frozenAt: '2026-10-06 10:00'
      }
    }))
    api.copyGasPlan.mockResolvedValue(detailData({
      plan: { id: 11, planNo: 'GP-1', planVersion: 2, status: '草稿' }
    }))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '次日调整' })

    const wrapper = mountDetail()
    await flushPromises()
    expect(wrapper.text()).toContain('冻结留存')
    expect(wrapper.text()).toContain('同意')
    expect(Array.from(wrapper.element.querySelectorAll('button'))
      .find(b => (b.textContent || '').includes('提交复核'))).toBeUndefined()

    Array.from(wrapper.element.querySelectorAll('button'))
      .find(b => (b.textContent || '').includes('复制新版本')).click()
    await flushPromises()
    expect(api.copyGasPlan).toHaveBeenCalledWith(10,
      expect.objectContaining({ changeNote: '次日调整' }))
  })

  it('版本对比调用接口并渲染配额差异', async () => {
    api.getGasPlan.mockResolvedValue(detailData({
      plan: { id: 11, planVersion: 2, status: '草稿' }
    }))
    api.compareGasPlans.mockResolvedValue({
      fromVersion: { id: 10, planVersion: 1 },
      toVersion: { id: 11, planVersion: 2 },
      areaDemandDeltaM3: 20,
      quotaDiffs: [
        { changeType: '调整', stationCode: 'RS-1', stationName: '调压站1', fromQuotaM3: 40, toQuotaM3: 50, deltaM3: 10 }
      ]
    })
    const wrapper = mountDetail()
    await flushPromises()

    wrapper.vm.compare.fromId = 10
    wrapper.vm.compare.toId = 11
    Array.from(wrapper.element.querySelectorAll('button'))
      .find(b => (b.textContent || '').trim() === '对比').click()
    await flushPromises()

    expect(api.compareGasPlans).toHaveBeenCalledWith(10, 11)
    expect(wrapper.text()).toContain('调压站1')
    expect(wrapper.text()).toContain('10')
  })
})
