<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import GasPlanCreateDialog from '../components/gasplan/GasPlanCreateDialog.vue'
import GasPlanDetail from '../components/gasplan/GasPlanDetail.vue'
import { useGasPlanList } from '../utils/useGasPlanList'
import { GP_STATUS, demandSourceLabel, gpStatusTagType } from '../utils/gasPlan'

const props = defineProps({ tenant: String })
const { rows, loading, total, error, query, load, search, changePage, changeSize, resetPage } =
  useGasPlanList()

watch(error, (msg) => { if (msg) ElMessage.error(msg) })

const createVisible = ref(false)
const detailVisible = ref(false)
const detailId = ref(null)

function openDetail(row) {
  detailId.value = row.id
  detailVisible.value = true
}

function closeDetail(changed) {
  detailVisible.value = false
  if (changed) load()
}

function openCopied(changed) {
  if (typeof changed === 'number') detailId.value = changed
  else if (changed) load()
}

watch(() => props.tenant, () => { resetPage() })
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">门站日输气计划 · 供气配额编排</span>
        <span class="panel-desc">草稿 → 待复核 → 已提交 → 已冻结；提交后复制新版本，冻结历史可查</span>
      </div>

      <div class="toolbar">
        <el-date-picker v-model="query.planDate" type="date" value-format="YYYY-MM-DD"
          placeholder="供气日期" clearable style="width:160px" @change="search" />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width:130px"
          @change="search">
          <el-option v-for="s in GP_STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="search">查询</el-button>
        <span class="spacer"></span>
        <el-button type="primary" @click="createVisible = true">新建计划</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="planNo" label="计划编号" width="150" />
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.planVersion }}</template>
        </el-table-column>
        <el-table-column prop="planDate" label="供气日期" width="120" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="gpStatusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="需求来源" width="110">
          <template #default="{ row }">{{ demandSourceLabel(row.demandSource) }}</template>
        </el-table-column>
        <el-table-column prop="areaDemandM3" label="片区需求 m³" width="140" />
        <el-table-column prop="createdBy" label="编制人" width="100" />
        <el-table-column prop="reviewedBy" label="复核人" width="100" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openDetail(row)">配额/版本</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-sizes="[10, 20, 50]"
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </div>

    <GasPlanCreateDialog v-if="createVisible" @done="createVisible = false; load()" @cancel="createVisible = false" />

    <el-drawer v-model="detailVisible" title="日输气计划详情 · 配额、版本与复核意见" size="78%">
      <GasPlanDetail v-if="detailVisible" :key="detailId" :version-id="detailId" @changed="openCopied" />
    </el-drawer>
  </div>
</template>
