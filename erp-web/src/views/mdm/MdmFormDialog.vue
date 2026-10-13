<template>
  <el-dialog :model-value="modelValue" :title="title" width="520px" destroy-on-close @update:model-value="emit('update:modelValue', $event)">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item v-for="f in fields" :key="f.prop" :label="f.label" :prop="f.prop">
        <el-input v-model="form[f.prop]" :maxlength="f.max" :disabled="isEdit && f.createOnly" :data-test="`field-${f.prop}`" />
        <div v-if="f.createOnly" class="field-hint" :data-test="`hint-${f.prop}`">{{ isEdit ? '编码创建后不可修改' : '编码保存后不可修改，且不能与已有（含已删除）编码重复' }}</div>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="form.status" data-test="field-status">
          <el-radio :value="1">启用</el-radio>
          <el-radio :value="0">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-test="cancel" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-test="save" @click="onSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createMdm, updateMdm } from '@/api/mdm'
import { MDM_FORMS, MDM_RESOURCES } from '@/config/mdm'

const props = defineProps({
  modelValue: Boolean,
  resource: { type: String, required: true },
  row: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref()
const saving = ref(false)
const form = reactive({})
const fields = computed(() => MDM_FORMS[props.resource])
const isEdit = computed(() => !!props.row?.id)
const title = computed(() => `${isEdit.value ? '编辑' : '新增'}${MDM_RESOURCES[props.resource].title}`)

const rules = computed(() => {
  const r = {}
  for (const f of fields.value) {
    r[f.prop] = []
    if (f.required) r[f.prop].push({ required: true, whitespace: true, message: `请输入${f.label}`, trigger: 'blur' })
    r[f.prop].push({ max: f.max, message: `${f.label}不能超过 ${f.max} 个字符`, trigger: 'blur' })
  }
  return r
})

function reset() {
  for (const k of Object.keys(form)) delete form[k]
  for (const f of fields.value) form[f.prop] = props.row?.[f.prop] ?? ''
  form.status = props.row?.status ?? 1
}
watch(() => props.modelValue, (v) => v && reset(), { immediate: true })

/** 只提交可编辑字段，去掉首尾空格；空串转 null */
function payload() {
  const body = { status: form.status }
  for (const f of fields.value) {
    if (isEdit.value && f.createOnly) continue
    const v = typeof form[f.prop] === 'string' ? form[f.prop].trim() : form[f.prop]
    body[f.prop] = v === '' ? null : v
  }
  return body
}

async function onSave() {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    if (isEdit.value) await updateMdm(props.resource, props.row.id, payload())
    else await createMdm(props.resource, payload())
    ElMessage.success('保存成功')
    emit('update:modelValue', false)
    emit('saved')
  } catch {
    // 拦截器已按业务码提示（40001 编码已存在含已删除、40900 并发冲突等），保留弹窗便于修改重试，保留弹窗便于修改重试
  } finally {
    saving.value = false
  }
}

defineExpose({ form, onSave, payload })
</script>

<style scoped>
.field-hint { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 2px; }
</style>
