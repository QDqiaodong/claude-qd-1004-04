<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { buildValidation, makeAllocations } from '../../utils/gasPlan'

const emit = defineEmits(['done', 'cancel'])

const loading = ref(false)
const saving = ref(false)
const context = ref(null)
const form = reactive({
  planNo: '',
  planDate: new Date().toISOString().slice(0, 10),
  areaDemandM3: 0,
  demandSource: 'METER_SNAPSHOT',
  demandReference: '',
  changeNote: '',
  operator: '',
  allocations: []
})
const validation = ref({ errors: [], warnings: [] })
const touched = ref(false)

async function loadContext() {
  loading.value = true
  try {
    const data = await api.gasPlanContext({ date: form.planDate || undefined })
    context.value = data
    form.allocations = makeAllocations(data)
    form.areaDemandM3 = data.areaDemandM3 || 0
    refreshValidation()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(() => form.allocations, () => { if (touched.value) refreshValidation() }, { deep: true })
watch(() => [form.areaDemandM3, form.demandSource], () => { if (touched.value) refreshValidation() })

function refreshValidation() {
  validation.value = buildValidation(form, context.value || {})
}

function useMeterDemand() {
  form.demandSource = 'METER_SNAPSHOT'
  form.areaDemandM3 = context.value?.areaDemandM3 || 0
  refreshValidation()
}

async function save() {
  touched.value = true
  refreshValidation()
  if (!form.planNo.trim()) return ElMessage.error('计划编号不能为空')
  if (!form.planDate) return ElMessage.error('计划日期不能为空')
  if (!form.operator.trim()) return ElMessage.error('操作人不能为空')

  saving.value = true
  try {
    const payload = {
      ...form,
      planNo: form.planNo.trim(),
      demandReference: form.demandReference.trim() || null,
      changeNote: form.changeNote.trim() || null,
      operator: form.operator.trim(),
      allocations: form.allocations.map(a => ({
        stationType: a.stationType,
        stationId: a.stationId,
        quotaM3: Number(a.quotaM3)
      }))
    }
    await api.createGasPlan(payload)
    ElMessage.success('供气计划草稿已创建')
    emit('done')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(loadContext)
</script>

<template>
  <el-dialog model-value title="新建日输气计划" width="900px" @close="emit('cancel')">
    <div v-loading="loading">
      <el-form label-width="110px" size="small">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="计划编号" required>
              <el-input v-model="form.planNo" placeholder="如 GP-20261006-A" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="计划日期" required>
              <el-date-picker v-model="form.planDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="操作人" required>
              <el-input v-model="form.operator" placeholder="编制人" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="需求来源" required>
              <el-select v-model="form.demandSource" style="width:100%" @change="refreshValidation">
                <el-option label="抄表快照" value="METER_SNAPSHOT" />
                <el-option label="人工填报" value="MANUAL" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="片区需求 m³" required>
              <el-input-number v-model="form.areaDemandM3" :min="0" :precision="2" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-button size="small" @click="useMeterDemand">采用抄表快照需求</el-button>
          </el-col>
        </el-row>
        <el-form-item label="需求依据">
          <el-input v-model="form.demandReference" placeholder="抄表批次/人工填报说明" />
        </el-form-item>
        <el-form-item label="变更说明">
          <el-input v-model="form.changeNote" type="textarea" :rows="2" placeholder="本版编排说明" />
        </el-form-item>
      </el-form>

      <div class="quota-validation" v-if="touched || validation.errors.length">
        <el-alert v-for="m in validation.errors" :key="m" :title="m" type="error" :closable="false" show-icon />
        <el-alert v-for="m in validation.warnings" :key="m" :title="m" type="warning" :closable="false" show-icon />
      </div>

      <el-table :data="form.allocations" size="small" border max-height="360">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ row.stationType === 'GATE_STATION' ? '门站' : '调压站' }}</template>
        </el-table-column>
        <el-table-column prop="stationCode" label="编号" width="110" />
        <el-table-column prop="stationName" label="站点" />
        <el-table-column prop="capacityM3" label="能力上限 m³" width="130" />
        <el-table-column label="配额 m³" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.quotaM3" :precision="2" size="small" style="width:140px"
              :class="{ 'quota-danger': Number(row.quotaM3) < 0 || Number(row.quotaM3) > Number(row.capacityM3) }" />
          </template>
        </el-table-column>
      </el-table>
      <div class="quota-summary">
        门站合计：<b>{{ validation.gateTotal }}</b> / {{ validation.gateCapacity }} m³；
        调压站合计：<b>{{ validation.regulatorTotal }}</b> / {{ validation.regulatorCapacity }} m³
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">创建草稿</el-button>
    </template>
  </el-dialog>
</template>
