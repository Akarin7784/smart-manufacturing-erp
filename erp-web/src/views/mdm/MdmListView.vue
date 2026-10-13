<template>
  <div class="mdm-list">
    <div class="toolbar">
      <el-input v-model="keyword" :placeholder="`按编码/名称搜索${config.title}`" clearable style="width: 280px" data-test="keyword" @keyup.enter="onSearch" @clear="onSearch" />
      <el-button type="primary" data-test="search" @click="onSearch">搜索</el-button>
      <el-button data-test="reset" @click="onReset">重置</el-button>
      <el-button v-if="canWrite" type="success" data-test="create" @click="openForm(null)">新增{{ config.title }}</el-button>
    </div>
    <el-table v-loading="loading" :data="records" border stripe empty-text="暂无数据" data-test="table">
      <el-table-column type="index" label="#" width="60" :index="(i) => (page - 1) * size + i + 1" />
      <el-table-column v-for="c in config.columns" :key="c.prop" :prop="c.prop" :label="c.label" :width="c.width">
        <template v-if="c.type === 'status'" #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
        <template v-else-if="c.type === 'datetime'" #default="{ row }">{{ formatDateTime(row[c.prop]) }}</template>
      </el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-test="edit" @click="openForm(row)">编辑</el-button>
          <el-button link type="danger" data-test="delete" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      class="pager"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :current-page="page"
      :page-size="size"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
    <MdmFormDialog v-model="formVisible" :resource="resource" :row="editingRow" @saved="refresh" @stale="refresh" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteMdm, pageMdm } from '@/api/mdm'
import { useAuthStore } from '@/stores/auth'
import MdmFormDialog from './MdmFormDialog.vue'
import { MDM_RESOURCES } from '@/config/mdm'
import { ErrorCode } from '@/api/errorCodes'
import { formatDateTime } from '@/utils/format'

const props = defineProps({ resource: { type: String, required: true } })
const config = computed(() => MDM_RESOURCES[props.resource])

const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)
const formVisible = ref(false)
const editingRow = ref(null)

const auth = useAuthStore()
const canWrite = computed(() => auth.hasAuthority('mdm:write'))

function openForm(row) {
  editingRow.value = row
  formVisible.value = true
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除${config.value.title}「${row.name}」（${row.code}）吗？`, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    // 失败提示由拦截器按业务码统一给出（如 40901 被引用 -> 建议改为停用），此处不再重复提示
    await deleteMdm(props.resource, row.id)
    ElMessage.success('删除成功')
    // 当前页删空时回退一页
    if (records.value.length === 1 && page.value > 1) page.value -= 1
    await refresh()
  } catch (e) {
    // 已由拦截器提示；40900 并发冲突时自动刷新列表
    if (e?.code === ErrorCode.CONCURRENT_MODIFIED) await refresh()
  }
}

// 后端约定：1=启用，0=停用（默认 1）
const statusText = (s) => (s === 1 ? '启用' : s === 0 ? '停用' : '-')
const statusTag = (s) => (s === 1 ? 'success' : 'info')

/**
 * 当前请求：{ key, promise, controller }。
 * - 相同参数的并发调用复用同一请求（去重）；force=true 时强制重新请求（保存/删除后刷新）
 * - 新请求会 abort 被取代的旧请求
 * - 结果是否生效按「对象身份」判断，而不是 key：A→B→A 快速切换时，第一个 A 的返回不会覆盖或提前清掉第二个 A
 */
let current = null

function load({ force = false } = {}) {
  const params = { page: page.value, size: size.value }
  if (keyword.value.trim()) params.keyword = keyword.value.trim()
  const resource = props.resource
  const key = `${resource}?${JSON.stringify(params)}`
  if (!force && current?.key === key) return current.promise
  current?.controller.abort()
  const entry = { key, controller: new AbortController() }
  entry.promise = (async () => {
    loading.value = true
    try {
      const data = await pageMdm(resource, params, { signal: entry.controller.signal })
      if (current !== entry) return // 已被取代，丢弃
      records.value = data?.records || []
      total.value = Number(data?.total || 0)
    } catch {
      if (current !== entry) return
      records.value = []
      total.value = 0
    } finally {
      if (current === entry) {
        current = null
        loading.value = false
      }
    }
  })()
  current = entry
  return entry.promise
}

/** 保存/删除/并发冲突后的刷新：绕过去重 */
const refresh = () => load({ force: true })

onBeforeUnmount(() => current?.controller.abort())

function onSearch() { page.value = 1; load() }
function onReset() { keyword.value = ''; page.value = 1; load() }
function onPageChange(p) { page.value = p; load() }
function onSizeChange(s) { size.value = s; page.value = 1; load() }

// 首次加载只在 onMounted 触发一次；resource 变化（同一组件实例复用时）再重新加载
onMounted(load)
watch(() => props.resource, () => { keyword.value = ''; page.value = 1; load() })

defineExpose({ refresh, openForm, onDelete, canWrite, formVisible, editingRow, load, onSearch, onReset, onPageChange, onSizeChange, records, total, page, size, keyword })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
