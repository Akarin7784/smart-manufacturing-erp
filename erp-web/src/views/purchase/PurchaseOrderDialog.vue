<template>
  <el-dialog :model-value="modelValue" :title="isEdit ? '编辑采购单' : '新建采购单'" width="860px" destroy-on-close data-testid="po-dialog" @update:model-value="onVisible">
    <el-form label-width="80px">
      <el-form-item label="供应商" required>
        <el-select v-model="form.supplierId" filterable placeholder="请选择供应商" data-test="field-supplier" style="width: 100%">
          <el-option v-for="s in suppliers" :key="s.id" :label="`${s.code} ${s.name}`" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="仓库" required>
        <el-select v-model="form.warehouseId" filterable placeholder="请选择仓库" data-test="field-warehouse" style="width: 100%">
          <el-option v-for="w in warehouses" :key="w.id" :label="`${w.code} ${w.name}`" :value="w.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" maxlength="255" data-test="field-remark" />
      </el-form-item>
    </el-form>
    <el-table :data="form.items" border size="small" data-test="items">
      <el-table-column label="物料" min-width="200">
        <template #default="{ row }">
          <el-select v-model="row.materialId" filterable placeholder="物料" style="width: 100%">
            <el-option v-for="m in materials" :key="m.id" :label="`${m.code} ${m.name}`" :value="m.id" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="170">
        <template #default="{ row, $index }">
          <el-input v-model="row.qty" data-testid="po-qty" placeholder="数量" />
          <div v-if="lineErrors[$index]?.qty" class="err">{{ lineErrors[$index].qty }}</div>
        </template>
      </el-table-column>
      <el-table-column label="单价" width="170">
        <template #default="{ row, $index }">
          <el-input v-model="row.price" data-testid="po-price" placeholder="单价" />
          <div v-if="lineErrors[$index]?.price" class="err">{{ lineErrors[$index].price }}</div>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="180">
        <template #default="{ $index }"><span data-testid="po-amount">{{ formatMoneyDisplay(lineAmounts[$index]) }}</span></template>
      </el-table-column>
      <el-table-column width="70">
        <template #default="{ $index }"><el-button link type="danger" :disabled="form.items.length <= 1" @click="form.items.splice($index, 1)">删除</el-button></template>
      </el-table-column>
    </el-table>
    <div class="items-footer">
      <el-button size="small" data-test="add-item" @click="addItem">添加明细</el-button>
      <span>合计：<b data-testid="po-total">{{ formatMoneyDisplay(total) }}</b></span>
    </div>
    <div v-if="errorText" class="err dialog-err" data-test="dialog-error">{{ errorText }}</div>
    <template #footer>
      <el-button data-test="cancel" @click="onVisible(false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="saving" data-testid="po-save" @click="onSave">{{ saving && inProgress ? '处理中' : '保存' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createPurchaseOrder, updatePurchaseOrder, getPurchaseOrder } from '@/api/purchase'
import { ErrorCode, ERROR_MESSAGES } from '@/api/errorCodes'
import { validateQty, validatePrice, validateAmount, lineAmount, sumAmounts, formatMoneyDisplay, newIdempotencyKey } from '@/utils/money'

import { PO_MSG } from '@/config/purchase'

const props = defineProps({
  modelValue: Boolean,
  /** 编辑时传入详情 {order, items}；新建为 null */
  detail: { type: Object, default: null },
  suppliers: { type: Array, default: () => [] },
  warehouses: { type: Array, default: () => [] },
  materials: { type: Array, default: () => [] },
  /** 40904 自动重试间隔（毫秒），测试可调小 */
  retryDelay: { type: Number, default: 1000 },
  maxAutoRetries: { type: Number, default: 3 }
})
const emit = defineEmits(['update:modelValue', 'saved', 'stale'])

const form = reactive({ supplierId: null, warehouseId: null, remark: '', items: [] })
const saving = ref(false)
const inProgress = ref(false)
const errorText = ref('')
const lineErrors = ref([])
/** 当前编辑的订单（含 version），40900 重新拉取后更新 */
const editing = ref(null)
/** 新建时的幂等键：打开弹窗生成，重试复用，成功或关闭后丢弃 */
const idemKey = ref(null)

const isEdit = computed(() => !!editing.value?.order?.id)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function emptyLine() { return { materialId: null, qty: '', price: '' } }
function addItem() { form.items.push(emptyLine()) }

function fill(detail) {
  editing.value = detail
  const o = detail?.order
  form.supplierId = o?.supplierId ?? null
  form.warehouseId = o?.warehouseId ?? null
  form.remark = o?.remark ?? ''
  form.items = detail?.items?.length
    ? detail.items.map((i) => ({ materialId: i.materialId, qty: String(i.qty ?? ''), price: String(i.price ?? '') }))
    : [emptyLine()]
}

function reset() {
  errorText.value = ''
  lineErrors.value = []
  inProgress.value = false
  fill(props.detail)
  idemKey.value = props.detail?.order?.id ? null : newIdempotencyKey()
}
watch(() => props.modelValue, (v) => {
  if (v) reset()
  else idemKey.value = null
}, { immediate: true })

function onVisible(v) {
  if (!v) idemKey.value = null
  emit('update:modelValue', v)
}

const lineAmounts = computed(() => form.items.map((it) => (validateQty(it.qty) || validatePrice(it.price) ? '' : lineAmount(it.qty, it.price))))
const total = computed(() => sumAmounts(lineAmounts.value.filter(Boolean)))

/** 前端校验：返回首个错误文案或 null */
function validate() {
  const errs = form.items.map((it) => ({ qty: validateQty(it.qty), price: validatePrice(it.price) }))
  lineErrors.value = errs
  if (!form.supplierId) return '请选择供应商'
  if (!form.warehouseId) return '请选择仓库'
  if (!form.items.length) return '请至少添加一行明细'
  for (let i = 0; i < form.items.length; i++) {
    if (!form.items[i].materialId) return `第 ${i + 1} 行请选择物料`
    if (errs[i].qty) return `第 ${i + 1} 行${errs[i].qty}`
    if (errs[i].price) return `第 ${i + 1} 行${errs[i].price}`
    const a = validateAmount(lineAmounts.value[i])
    if (a) return `第 ${i + 1} 行${a}`
  }
  const t = validateAmount(total.value)
  if (t) return `合计${t}`
  return null
}

/** 请求体：数量/单价以字符串原样提交（去首尾空格），后端字段名 qty/price */
function payload() {
  const body = {
    supplierId: form.supplierId,
    warehouseId: form.warehouseId,
    remark: form.remark?.trim() || null,
    items: form.items.map((it) => ({ materialId: it.materialId, qty: String(it.qty).trim(), price: String(it.price).trim() }))
  }
  // 乐观锁：原样带回查询到的 version
  if (isEdit.value) body.version = editing.value.order.version
  return body
}

function close() {
  idemKey.value = null
  emit('update:modelValue', false)
}

async function send() {
  if (isEdit.value) return updatePurchaseOrder(editing.value.order.id, payload(), { silent: true })
  return createPurchaseOrder(payload(), { silent: true, headers: { 'Idempotency-Key': idemKey.value } })
}

async function onConflict() {
  // 40900：提示 + 重新拉取；不是草稿了按 40903 处理
  const id = editing.value?.order?.id
  if (!id) return
  try {
    const fresh = await getPurchaseOrder(id, { silent: true })
    if (fresh?.order?.status !== 'DRAFT') return onStatusChanged()
    fill(fresh)
    errorText.value = PO_MSG.conflict
    ElMessage.error(PO_MSG.conflict)
  } catch {
    errorText.value = PO_MSG.conflict
    ElMessage.error(PO_MSG.conflict)
  }
}

function onStatusChanged() {
  ElMessage.error(ERROR_MESSAGES[ErrorCode.STATUS_CHANGED])
  close()
  emit('stale', editing.value?.order?.id ?? null)
}

async function onSave() {
  if (saving.value) return
  errorText.value = ''
  const v = validate()
  if (v) { errorText.value = v; return }
  saving.value = true
  inProgress.value = false
  try {
    let attempt = 0
    for (;;) {
      try {
        const data = await send()
        ElMessage.success('保存成功')
        close()
        emit('saved', data)
        return
      } catch (e) {
        if (e?.code === ErrorCode.REQUEST_IN_PROGRESS && attempt < props.maxAutoRetries) {
          attempt++
          inProgress.value = true
          await sleep(props.retryDelay)
          continue
        }
        handleError(e)
        return
      }
    }
  } finally {
    saving.value = false
    inProgress.value = false
  }
}

function handleError(e) {
  switch (e?.code) {
    case ErrorCode.STATUS_CHANGED: return onStatusChanged()
    case ErrorCode.CONCURRENT_MODIFIED: return onConflict()
    case ErrorCode.DOC_NO_BUSY:
      errorText.value = PO_MSG.docNoBusy
      return ElMessage.error(PO_MSG.docNoBusy)
    case ErrorCode.REQUEST_IN_PROGRESS:
      errorText.value = PO_MSG.maybeSubmitted
      return ElMessage.warning(PO_MSG.maybeSubmitted)
    case ErrorCode.DUPLICATE_REQUEST:
    default: {
      const text = e?.message || '保存失败'
      errorText.value = text
      return ElMessage.error(text)
    }
  }
}

defineExpose({ form, onSave, payload, validate, idemKey, errorText, saving, inProgress, editing, lineAmounts, total })
</script>

<style scoped>
.items-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.err { color: #f56c6c; font-size: 12px; line-height: 1.4; }
.dialog-err { margin-top: 8px; }
</style>
