export const GP_STATUS = ['草稿', '待复核', '已提交', '已冻结']

export const GP_ACTIONS = {
  submit: { label: '提交复核', confirm: '提交后版本进入待复核，配额将不可直接改写，确认提交？' },
  approve: { label: '复核通过', confirm: '复核通过后版本进入已提交，确认通过？' },
  reject: { label: '驳回', confirm: '驳回后版本回到草稿，已保存配额将保留，确认驳回？' },
  freeze: { label: '冻结版本', confirm: '冻结后历史配额和复核意见将永久留存，确认冻结？' },
  copy: { label: '复制新版本', confirm: '将从当前版本复制生成下一版草稿，确认继续？' }
}

export function gpStatusTagType(status) {
  if (status === '待复核') return 'warning'
  if (status === '已提交') return 'success'
  if (status === '已冻结') return 'info'
  return undefined
}

export function stationTypeLabel(type) {
  return type === 'GATE_STATION' ? '门站' : type === 'REGULATOR_STATION' ? '调压站' : type
}

export function demandSourceLabel(source) {
  if (source === 'METER_SNAPSHOT') return '抄表快照'
  if (source === 'MANUAL') return '人工填报'
  return source || '未选择'
}

export function toNumber(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

export function buildValidation(form, context = {}) {
  const gateStations = context.gateStations || []
  const regulatorStations = context.regulatorStations || []
  const errors = []
  const warnings = []
  const rows = form.allocations || []
  let gateTotal = 0
  let regulatorTotal = 0
  let gateCapacity = 0
  let regulatorCapacity = 0

  gateStations.forEach((g) => {
    gateCapacity += toNumber(g.inletCapacityM3)
  })
  regulatorStations.forEach((r) => {
    regulatorCapacity += toNumber(r.outletCapacityM3)
  })

  if (!form.demandSource) errors.push('缺少需求来源')
  if (toNumber(form.areaDemandM3) < 0) errors.push('片区需求量不能为负')

  const gateIds = new Set()
  const regulatorIds = new Set()
  rows.forEach((row) => {
    const quota = toNumber(row.quotaM3)
    if (row.stationType === 'GATE_STATION') {
      const station = gateStations.find(g => g.id === row.stationId)
      gateIds.add(row.stationId)
      gateTotal += quota
      if (quota < 0) errors.push(`${station?.stationName || row.stationId} 配额不能为负`)
      if (station && quota > toNumber(station.inletCapacityM3)) {
        errors.push(`${station.stationName} 配额超过进站能力`)
      }
    } else {
      const station = regulatorStations.find(r => r.id === row.stationId)
      regulatorIds.add(row.stationId)
      regulatorTotal += quota
      if (quota < 0) errors.push(`${station?.stationName || row.stationId} 配额不能为负`)
      if (station && quota > toNumber(station.outletCapacityM3)) {
        errors.push(`${station.stationName} 配额超过出口上限`)
      }
    }
  })

  gateStations.forEach((g) => {
    if (!gateIds.has(g.id)) errors.push(`缺少门站配额：${g.stationName}`)
  })
  regulatorStations.forEach((r) => {
    if (!regulatorIds.has(r.id)) errors.push(`缺少调压站配额：${r.stationName}`)
  })
  if (gateTotal > gateCapacity) {
    errors.push(`门站配额合计 ${gateTotal} m³ 超过进站能力合计 ${gateCapacity} m³`)
  }
  if (regulatorTotal > regulatorCapacity) {
    errors.push('调压站配额合计超过出口能力合计')
  }
  if (regulatorTotal > gateTotal) warnings.push('调压站配额合计大于门站配额合计，请核对输配平衡')
  if (gateTotal < toNumber(form.areaDemandM3)) warnings.push('门站配额合计小于片区需求，可能无法满足当日供气')

  return {
    validForSubmit: errors.length === 0,
    errors,
    warnings,
    gateTotal,
    gateCapacity,
    regulatorTotal,
    regulatorCapacity
  }
}

export function makeAllocations(context) {
  const gates = (context.gateStations || []).map(g => ({
    stationType: 'GATE_STATION',
    stationId: g.id,
    stationCode: g.stationCode,
    stationName: g.stationName,
    capacityM3: g.inletCapacityM3,
    quotaM3: 0
  }))
  const regulators = (context.regulatorStations || []).map(r => ({
    stationType: 'REGULATOR_STATION',
    stationId: r.id,
    stationCode: r.stationCode,
    stationName: r.stationName,
    capacityM3: r.outletCapacityM3,
    quotaM3: 0
  }))
  return [...gates, ...regulators]
}
