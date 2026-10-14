<template>
  <div class="po-list">
    <div class="toolbar">
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 160px" data-test="status-filter" @change="onSearch">
        <el-option v-for="(v, k) in PO_STATUS" :key="k" :label="v.text" :value="k" />
      </el-select>
      <el-button data-test="refresh" @click="refresh">刷新</el-button>
      <el-button v-if="canWrite" type="success" data-test="create" @click="openCreate">新建采购单</el-button>
    </div>
    <el-table v-loading="loading" :data="records" border stripe row-key="id" empty-text="暂无数据" :row-class-name="({ row }) => `po-row-${row.id}`" data-test="table">
      <el-table-column prop="orderNo" label="单号" width="190">
        <template #default="{ row }"><span :data-testid="`po-row-${row.id}`">{{ row.orderNo }}</span></template>
      </el-table-column>
      <el-table-column label="供应商" min-width="140"><template #default="{ row }">{{ nameOf(suppliers, row.supplierId) }}</template></el-table-column>
      <el-table-column label="仓库" min-width="120"><template #default="{ row }">{{ nameOf(warehouses, row.warehouseId) }}</template></el-table-column>
      <el-table-column label="金额" min-width="180" align="right">
        <template #default="{ row }"><span data-testid="po-amount">{{ formatMoneyDisplay(row.totalAmount) }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="PO_STATUS[row.status]?.tag || 'info'">{{ PO_STATUS[row.status]?.text || row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="createdBy" label="申请人" width="110" />
      <el-table-column label="创建时间" width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canWrite && row.status === 'DRAFT'" link type="primary" data-test="edit" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canWrite && (row.status === 'DRAFT' || row.status === 'REJECTED')" link type="primary" data-testid="po-submit" :loading="busy[row.id]" @click="onSubmit(row)">提交</el-button>
          <template v-if="canDecide(row)">
            <el-button link type="success" data-testid="po-approve" :loading="busy[row.id]" @click="onDecide(row, true)">审批通过</el-button>
            <el-button link type="danger" data-testid="po-reject" :loading="busy[row.id]" @click="onDecide(row, false)">驳回</el-button>
          </template>
          <el-button v-if="canReceive && row.status === 'APPROVED'" link type="warning" data-testid="po-receive" :loading="busy[row.id]" @click="onReceive(row)">收货</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :total="total" :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]" @current-change="onPageChange" @size-change="onSizeChange" />
    <PurchaseOrderDialog v-model="dialogVisible" :detail="editingDetail" :suppliers="suppliers" :warehouses="warehouses" :materials="materials" @saved="refresh" @stale="onStale" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pagePurchaseOrders, getPurchaseOrder, submitPurchaseOrder, receivePurchaseOrder } from '@/api/purchase'
import { listApprovalTodo, decideApproval } from '@/api/workflow'
import { pageMdm } from '@/api/mdm'
import { useAuthStore } from '@/stores/auth'
import { ErrorCode } from '@/api/errorCodes'
import { PO_STATUS, PO_BIZ_TYPE, PO_MSG } from '@/config/purchase'
import { formatDateTime } from '@/utils/format'
import { formatMoneyDisplay, newIdempotencyKey } from '@/utils/money'
import PurchaseOrderDialog from './PurchaseOrderDialog.vue'

const auth = useAuthStore()
const canWrite = computed(() => auth.hasAuthority('purchase:write') || auth.hasAuthority('ROLE_ADMIN'))
const canReceive = computed(() => auth.hasAuthority('inventory:write') || auth.hasAuthority('ROLE_ADMIN'))

const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const loading = ref(false)
const busy = reactive({})
const todo = ref([])
const suppliers = ref([])
const warehouses = ref([])
const materials = ref([])
const dialogVisible = ref(false)
const editingDetail = ref(null)
/** 收货幂等键：每次收货动作生成，失败重试复用，成功后丢弃（按订单 id） */
const receiveKeys = reactive({})

const nameOf = (list, id) => list.find((x) => x.id === id)?.name ?? id ?? '-'

/** 当前节点审批人才显示：后端 /approvals/todo 已过滤掉申请人本人与非当前节点审批人；再兜底排除自己的单据 */
function todoOf(row) {
  return todo.value.find((t) => t.bizType === PO_BIZ_TYPE && t.bizId === row.id)
}
function canDecide(row) {
  if (row.status !== 'PENDING') return false
  if (row.createdBy && row.createdBy === auth.username) return false
  const t = todoOf(row)
  return !!t && t.applicant !== auth.username
}

async function loadTodo() {
  try { todo.value = (await listApprovalTodo({ silent: true })) || [] } catch { todo.value = [] }
}

async function loadMaster() {
  const get = async (r) => { try { return (await pageMdm(r, { page: 1, size: 500 }, { silent: true }))?.records || [] } catch { return [] } }
  ;[suppliers.value, warehouses.value, materials.value] = await Promise.all([get('suppliers'), get('warehouses'), get('materials')])
}

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (status.value) params.status = status.value
    const data = await pagePurchaseOrders(params)
    records.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
async function refresh() { await Promise.all([load(), loadTodo()]) }

/** 操作后按 id 重新拉取，替换列表行（version 已变） */
async function refetchRow(id) {
  try {
    const d = await getPurchaseOrder(id, { silent: true })
    const i = records.value.findIndex((r) => r.id === id)
    if (i >= 0 && d?.order) records.value.splice(i, 1, d.order)
    return d
  } catch {
    return null
  }
}

function openCreate() {
  editingDetail.value = null
  dialogVisible.value = true
}
async function openEdit(row) {
  try {
    const d = await getPurchaseOrder(row.id)
    if (d?.order?.status !== 'DRAFT') {
      ElMessage.error('单据状态已变更，请刷新后重试')
      await refetchRow(row.id)
      return
    }
    editingDetail.value = d
    dialogVisible.value = true
  } catch { /* 拦截器已提示 */ }
}
async function onStale(id) {
  if (id) await refetchRow(id)
  await refresh()
}

async function run(row, fn) {
  if (busy[row.id]) return
  busy[row.id] = true
  try { return await fn() } finally { busy[row.id] = false }
}

async function onSubmit(row) {
  await run(row, async () => {
    try {
      await submitPurchaseOrder(row.id)
      ElMessage.success('已提交审批')
    } catch { /* 拦截器已提示 */ }
    await refetchRow(row.id)
    await loadTodo()
  })
}

async function onDecide(row, approved) {
  const t = todoOf(row)
  if (!t) return
  let comment = ''
  if (!approved) {
    try {
      const r = await ElMessageBox.prompt('请输入驳回意见（可选）', '驳回', { confirmButtonText: '驳回', cancelButtonText: '取消', inputValue: '' })
      comment = r?.value || ''
    } catch { return }
  }
  await run(row, async () => {
    try {
      await decideApproval(t.id, { approved, comment })
      ElMessage.success(approved ? '已审批通过' : '已驳回，单据退回草稿')
    } catch { /* 拦截器已提示 */ }
    await refetchRow(row.id)
    await loadTodo()
  })
}

async function onReceive(row) {
  await run(row, async () => {
    if (!receiveKeys[row.id]) receiveKeys[row.id] = newIdempotencyKey()
    try {
      await receivePurchaseOrder(row.id, { silent: true, headers: { 'Idempotency-Key': receiveKeys[row.id] } })
      delete receiveKeys[row.id]
      ElMessage.success('收货入库成功')
    } catch (e) {
      if (e?.code === ErrorCode.DOC_NO_BUSY) ElMessage.error(PO_MSG.docNoBusy) // 保留 key，重试复用
      else if (e?.code === ErrorCode.REQUEST_IN_PROGRESS) ElMessage.warning(PO_MSG.maybeSubmitted)
      else {
        delete receiveKeys[row.id]
        ElMessage.error(e?.message || '收货失败')
      }
    }
    await refetchRow(row.id)
  })
}

function onSearch() { page.value = 1; load() }
function onPageChange(p) { page.value = p; load() }
function onSizeChange(s) { size.value = s; page.value = 1; load() }

onMounted(() => { refresh(); loadMaster() })

defineExpose({ records, todo, refresh, load, loadTodo, refetchRow, canDecide, onSubmit, onDecide, onReceive, openCreate, openEdit, onStale, receiveKeys, dialogVisible, editingDetail, canWrite, canReceive })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
