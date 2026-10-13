<template>
  <div class="mdm-list">
    <div class="toolbar">
      <el-input v-model="keyword" :placeholder="`按编码/名称搜索${config.title}`" clearable style="width: 280px" data-test="keyword" @keyup.enter="onSearch" @clear="onSearch" />
      <el-button type="primary" data-test="search" @click="onSearch">搜索</el-button>
      <el-button data-test="reset" @click="onReset">重置</el-button>
    </div>
    <el-table v-loading="loading" :data="records" border stripe empty-text="暂无数据" data-test="table">
      <el-table-column type="index" label="#" width="60" :index="(i) => (page - 1) * size + i + 1" />
      <el-table-column v-for="c in config.columns" :key="c.prop" :prop="c.prop" :label="c.label" :width="c.width">
        <template v-if="c.type === 'status'" #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
        <template v-else-if="c.type === 'datetime'" #default="{ row }">{{ formatDateTime(row[c.prop]) }}</template>
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
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { pageMdm } from '@/api/mdm'
import { MDM_RESOURCES } from '@/config/mdm'
import { formatDateTime } from '@/utils/format'

const props = defineProps({ resource: { type: String, required: true } })
const config = computed(() => MDM_RESOURCES[props.resource])

const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)

// 后端约定：1=启用，0=停用（默认 1）
const statusText = (s) => (s === 1 ? '启用' : s === 0 ? '停用' : '-')
const statusTag = (s) => (s === 1 ? 'success' : 'info')

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const data = await pageMdm(props.resource, params)
    records.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSearch() { page.value = 1; load() }
function onReset() { keyword.value = ''; page.value = 1; load() }
function onPageChange(p) { page.value = p; load() }
function onSizeChange(s) { size.value = s; page.value = 1; load() }

watch(() => props.resource, () => { keyword.value = ''; page.value = 1; load() }, { immediate: true })

defineExpose({ load, onSearch, onReset, onPageChange, onSizeChange, records, total, page, size, keyword })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
