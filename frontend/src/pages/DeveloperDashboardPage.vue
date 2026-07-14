<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { api, type UsageDashboard } from "../api";
const props = defineProps<{ memberId?: number | null }>();
const data = ref<UsageDashboard | null>(null); const loading = ref(false);
async function load() { if (!props.memberId) { data.value = null; return; } loading.value = true; try { data.value = await api.memberUsageDashboard(props.memberId); } catch (error) { ElMessage.error(error instanceof Error ? error.message : "开发者用量加载失败"); } finally { loading.value = false; } }
watch(() => props.memberId, () => void load()); onMounted(load);
</script>
<template><section class="page-stack" v-loading="loading"><header class="page-heading"><div><p class="eyebrow">Developer / personal usage</p><h1>我的开发者工作台</h1><p>按模型查看当前授权、周期额度与实际调用情况。</p></div><el-button @click="load">刷新</el-button></header><section v-if="data" class="surface"><el-table :data="data.modelEntitlements"><el-table-column prop="modelName" label="模型"/><el-table-column prop="quotaMode" label="周期"/><el-table-column label="上限"><template #default="scope">{{ scope.row.quotaLimit ?? '不限' }}</template></el-table-column><el-table-column label="当前周期"><template #default="scope">{{ scope.row.consumedTokens }} 已用 · {{ scope.row.frozenTokens }} 冻结 · {{ scope.row.remainingTokens ?? '不限' }} 剩余</template></el-table-column></el-table></section><section v-if="data" class="surface"><h2>近 7 日用量</h2><el-table :data="data.lastSevenDays" size="small"><el-table-column prop="day" label="日期"/><el-table-column prop="tokens" label="Token"/><el-table-column prop="amount" label="成本"/><el-table-column prop="requests" label="请求数"/></el-table></section><section v-else class="surface empty-state"><h2>请选择开发成员</h2><p>负责人也可在此作为开发者查看自己的模型权益。</p></section></section></template>
