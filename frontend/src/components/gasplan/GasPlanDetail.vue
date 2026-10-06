<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import {
  GP_ACTIONS, buildValidation, demandSourceLabel, gpStatusTagType, stationTypeLabel
} from '../../utils/gasPlan'

const props = defineProps({ versionId: { type: Number, required: true } })
const emit = defineEmits(['changed'])

const detail = ref(null)
const history = ref(null)
const context = ref(null)
const loading = ref(false)
const busy = ref(false)
const actionError = ref('')
const compare = reactive({ fromId: null, toId: null, result: null })

const form = reactive({
  planNo: '', planDate: '', areaDemandM3: 0, demandSource: '',
  demandReference: '', changeNote: '', operator: '', allocations: []
})
const localValidation = ref({ errors: [], warnings: [], gateTotal: 0, gateCapacity: 0, regulatorTotal: 0, regulatorCapacity: 0 })

const plan = computed(() => detail.value?.plan)
const quotas = computed(() => detail.value?.quotas || [])
const validation = computed(() => detail.value?.validation || { errors: [], warnings: [] })
const histories = computed(() => history.value?.histories || [])
const versions = computed(() => history.value?.versions || [])

async function load() {
  loading.value = true
  actionError.value = ''
  try {
    const [d, h, ctx] = await Promise.all([
      api.getGasPlan(props.versionId),
      api.getGasPlanHistory(props.versionId),
      api.gasPlanContext({})
    ])
    detail.value = d
    history.value = h
    context.value = ctx
    fillForm(d)
    refreshLocalValidation()
    if (!compare.fromId) compare.fromId = d.plan.sourceVersionId || (versions.value[0]?.id)
    compare.toId = d.plan.id
  } catch (e) {
    actionError.value = e.message
  } finally {
    loading.value = false
  }
}

function fillForm(d) {
  Object.assign(form, {
    planNo: d.plan.planNo,
    planDate: d.plan.planDate,
    areaDemandM3: d.plan.areaDemandM3,
    demandSource: d.plan.demandSource || '',
    demandReference: d.plan.demandReference || '',
    changeNote: d.plan.changeNote || '',
    operator: '',
    allocations: (d.quotas || []).map(q => ({ ...q }))
  })
}

function refreshLocalValidation() {
  localValidation.value = buildValidation(form, context.value || {})
}

async function saveDraft() {
  actionError.value = ''
  if (!form.operator.trim()) return ElMessage.error('请填写操作人')
  busy.value = true
  try {
    detail.value = await api.updateGasPlan(props.versionId, {
      ...form,
      demandReference: form.demandReference.trim() || null,
      changeNote: form.changeNote.trim() || null,
      operator: form.operator.trim(),
      allocations: form.allocations.map(a => ({
        stationType: a.stationType,
        stationId: a.stationId,
        quotaM3: Number(a.quotaM3)
      }))
    })
    fillForm(detail.value)
    ElMessage.success('草稿配额已保存')
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

async function askOpinion(title, requiredText, required = true) {
  try {
    const { value } = await ElMessageBox.prompt(requiredText, title, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputValidator: v => (!required || (v && v.trim())) || '请填写说明'
    })
    return value ? value.trim() : ''
  } catch {
    return null
  }
}

async function askOperator(message = '请输入操作人') {
  try {
    const { value } = await ElMessageBox.prompt(message, '操作确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: v => (v && v.trim()) || '操作人不能为空'
    })
    return value.trim()
  } catch {
    return null
  }
}

async function runAction(action) {
  actionError.value = ''
  const meta = GP_ACTIONS[action]
  try {
    await ElMessageBox.confirm(meta.confirm, meta.label, { type: 'warning' })
  } catch { return }

  const operator = await askOperator()
  if (!operator) return
  let opinion = null
  if (['approve', 'reject', 'freeze'].includes(action)) {
    const label = action === 'approve' ? '复核意见' : action === 'reject' ? '驳回原因' : '冻结说明'
    opinion = await askOpinion(meta.label, label + '（必填）')
    if (!opinion) return
  }
  busy.value = true
  try {
    detail.value = await api.gasPlanAction(props.versionId, action, { operator, opinion })
    ElMessage.success(meta.label + '成功')
    emit('changed', true)
    await load()
  } catch (e) {
    actionError.value = e.message
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

async function copyVersion() {
  actionError.value = ''
  try {
    await ElMessageBox.confirm(GP_ACTIONS.copy.confirm, '复制新版本', { type: 'warning' })
  } catch { return }
  const operator = await askOperator()
  if (!operator) return
  const changeNote = await askOpinion('复制新版本', '请填写本次变更说明（可留空）', false)
  busy.value = true
  try {
    const copied = await api.copyGasPlan(props.versionId, { operator, changeNote })
    ElMessage.success('已复制为新草稿版本 v' + copied.plan.planVersion)
    emit('changed', copied.plan.id)
  } catch (e) {
    actionError.value = e.message
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

async function runCompare() {
  if (!compare.fromId || !compare.toId) return ElMessage.error('请选择两个版本')
  try {
    compare.result = await api.compareGasPlans(compare.fromId, compare.toId)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function rowClass({ row }) {
  if (row.negative || row.overCapacity) return 'quota-row-danger'
  return ''
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="gp-detail">
    <div v-if="actionError" class="err conflict-box">{{ actionError }}</div>
    <template v-if="plan">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="计划编号">{{ plan.planNo }}</el-descriptions-item>
        <el-descriptions-item label="业务版本">v{{ plan.planVersion }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="gpStatusTagType(plan.status)">{{ plan.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="供气日期">{{ plan.planDate }}</el-descriptions-item>
        <el-descriptions-item label="需求来源">{{ demandSourceLabel(plan.demandSource) }}</el-descriptions-item>
        <el-descriptions-item label="片区需求">{{ plan.areaDemandM3 }} m³</el-descriptions-item>
        <el-descriptions-item label="提交人">{{ plan.submittedBy || '—' }}<span v-if="plan.submittedAt"> / {{ plan.submittedAt }}</span></el-descriptions-item>
        <el-descriptions-item label="复核人">{{ plan.reviewedBy || '—' }}<span v-if="plan.reviewedAt"> / {{ plan.reviewedAt }}</span></el-descriptions-item>
        <el-descriptions-item label="冻结人">{{ plan.frozenBy || '—' }}<span v-if="plan.frozenAt"> / {{ plan.frozenAt }}</span></el-descriptions-item>
        <el-descriptions-item label="需求依据" :span="3">{{ plan.demandReference || '—' }}</el-descriptions-item>
        <el-descriptions-item label="变更说明" :span="3">{{ plan.changeNote || '—' }}</el-descriptions-item>
        <el-descriptions-item label="最近复核意见" :span="3">{{ plan.reviewOpinion || '—' }}</el-descriptions-item>
      </el-descriptions>

      <div class="action-bar">
        <el-button v-if="plan.status === '草稿'" type="primary" size="small" :loading="busy"
          :disabled="localValidation.errors.length > 0" @click="runAction('submit')">提交复核</el-button>
        <el-button v-if="plan.status === '待复核'" type="success" size="small" :loading="busy" @click="runAction('approve')">复核通过</el-button>
        <el-button v-if="plan.status === '待复核'" type="danger" size="small" :loading="busy" @click="runAction('reject')">驳回</el-button>
        <el-button v-if="plan.status === '已提交'" type="warning" size="small" :loading="busy" @click="runAction('freeze')">冻结版本</el-button>
        <el-button v-if="plan.status === '已提交' || plan.status === '已冻结'" size="small" :loading="busy" @click="copyVersion">
          复制新版本
        </el-button>
        <span v-if="plan.status === '草稿' && localValidation.errors.length" class="action-hint danger-text">
          修正能力或需求问题后才能提交（仍可保存草稿）
        </span>
        <span v-if="plan.status === '已冻结'" class="action-hint">已冻结版本只读，历史配额和复核意见可在下方查阅</span>
      </div>

      <el-alert v-for="m in validation.errors" :key="m" :title="m" type="error" :closable="false" show-icon />
      <el-alert v-for="m in validation.warnings" :key="m" :title="m" type="warning" :closable="false" show-icon />
      <div class="quota-summary">
        门站合计 <b :class="{ 'danger-text': validation.gateTotalOverCapacity }">{{ validation.gateTotalQuotaM3 }}</b>
        / {{ validation.gateTotalCapacityM3 }} m³；
        调压站合计 <b>{{ validation.regulatorTotalQuotaM3 }}</b> / {{ validation.regulatorTotalCapacityM3 }} m³
      </div>

      <div v-if="plan.status === '草稿'" class="draft-editor">
        <div class="section-title">草稿配额编排</div>
        <el-row :gutter="12">
          <el-col :span="6">
            <label>需求来源</label>
            <el-select v-model="form.demandSource" size="small" @change="refreshLocalValidation">
              <el-option label="抄表快照" value="METER_SNAPSHOT" />
              <el-option label="人工填报" value="MANUAL" />
            </el-select>
          </el-col>
          <el-col :span="6">
            <label>片区需求 m³</label>
            <el-input-number v-model="form.areaDemandM3" size="small" @change="refreshLocalValidation" />
          </el-col>
          <el-col :span="8">
            <label>操作人</label>
            <el-input v-model="form.operator" size="small" placeholder="保存人" />
          </el-col>
        </el-row>
        <el-input v-model="form.changeNote" size="small" placeholder="变更说明" class="change-note" />
        <el-table :data="form.allocations" size="small" border max-height="320">
          <el-table-column label="类型" width="80"><template #default="{ row }">{{ stationTypeLabel(row.stationType) }}</template></el-table-column>
          <el-table-column prop="stationCode" label="编号" width="110" />
          <el-table-column prop="stationName" label="站点" />
          <el-table-column prop="capacityM3" label="能力上限" width="120" />
          <el-table-column label="配额" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.quotaM3" size="small" :precision="2"
                @change="refreshLocalValidation" />
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-for="m in localValidation.errors" :key="m" :title="m" type="error" :closable="false" show-icon />
        <el-button class="save-draft" size="small" type="primary" :loading="busy" @click="saveDraft">保存草稿</el-button>
      </div>

      <el-table v-if="plan.status !== '草稿'" :data="quotas" size="small" border :row-class-name="rowClass">
        <el-table-column label="类型" width="80"><template #default="{ row }">{{ stationTypeLabel(row.stationType) }}</template></el-table-column>
        <el-table-column prop="stationCode" label="编号" width="110" />
        <el-table-column prop="stationName" label="站点" />
        <el-table-column prop="dailyFlowM3" label="日输气量" width="120" />
        <el-table-column prop="capacityM3" label="能力上限快照" width="130" />
        <el-table-column prop="quotaM3" label="配额" width="120" />
        <el-table-column label="校验" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.negative || row.overCapacity" type="danger">超限</el-tag>
            <el-tag v-else type="success">正常</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-tabs class="history-tabs">
        <el-tab-pane label="历史版本 / 复核意见">
          <el-timeline>
            <el-timeline-item v-for="h in histories" :key="h.id" :timestamp="`${h.createdAt} · ${h.operatorName}`">
              <b>{{ h.action }}</b>
              <span v-if="h.fromStatus">（{{ h.fromStatus }} → {{ h.toStatus }}）</span>
              <div>{{ h.note || '—' }}</div>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
        <el-tab-pane label="版本对比">
          <div class="compare-bar">
            <el-select v-model="compare.fromId" size="small" placeholder="旧版本" style="width:180px">
              <el-option v-for="v in versions" :key="v.id" :label="`v${v.planVersion} ${v.status}`" :value="v.id" />
            </el-select>
            <span>→</span>
            <el-select v-model="compare.toId" size="small" placeholder="新版本" style="width:180px">
              <el-option v-for="v in versions" :key="v.id" :label="`v${v.planVersion} ${v.status}`" :value="v.id" />
            </el-select>
            <el-button size="small" type="primary" @click="runCompare">对比</el-button>
          </div>
          <el-table v-if="compare.result" :data="compare.result.quotaDiffs" size="small" border>
            <el-table-column prop="changeType" label="变化" width="80" />
            <el-table-column prop="stationCode" label="编号" width="110" />
            <el-table-column prop="stationName" label="站点" />
            <el-table-column prop="fromQuotaM3" label="旧配额" width="110" />
            <el-table-column prop="toQuotaM3" label="新配额" width="110" />
            <el-table-column prop="deltaM3" label="差异" width="110" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>
