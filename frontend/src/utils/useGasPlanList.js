import { reactive, ref } from 'vue'
import api from '../api'

/** 供气计划版本列表：按日期、状态筛选，慢响应不覆盖新请求，切换片区回到第一页。 */
export function useGasPlanList() {
  const rows = ref([])
  const loading = ref(false)
  const total = ref(0)
  const error = ref('')
  const query = reactive({
    planDate: '',
    status: '',
    page: 1,
    size: 10
  })

  let ticket = 0

  function buildParams() {
    return {
      planDate: query.planDate || undefined,
      status: query.status || undefined,
      page: query.page - 1,
      size: query.size
    }
  }

  async function load() {
    const mine = ++ticket
    loading.value = true
    error.value = ''
    try {
      const data = await api.listGasPlans(buildParams())
      if (mine !== ticket) return null
      rows.value = data.items || []
      total.value = data.total || 0
      return data
    } catch (e) {
      if (mine === ticket) error.value = (e && e.message) || '查询失败'
      return null
    } finally {
      if (mine === ticket) loading.value = false
    }
  }

  function search() {
    query.page = 1
    return load()
  }

  function changePage(page) {
    query.page = page
    return load()
  }

  function changeSize(size) {
    query.size = size
    query.page = 1
    return load()
  }

  function resetPage() {
    query.page = 1
    return load()
  }

  return { rows, loading, total, error, query, load, search, changePage, changeSize, resetPage }
}
