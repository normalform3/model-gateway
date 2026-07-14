<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import QuotaVisualizationPanel from "../components/QuotaVisualizationPanel.vue";
import { api, type DashboardOverview, type QuotaSummary } from "../api";

const data = ref<DashboardOverview | null>(null);
const quotaSummary = ref<QuotaSummary | null>(null);
const loading = ref(false);
const policy = reactive({ globalRpm: 10000, globalConcurrency: 1000 });

async function load() {
  loading.value = true;
  try {
    const [overview, summary] = await Promise.all([api.dashboard(), api.quotaSummary()]);
    data.value = overview; quotaSummary.value = summary;
    policy.globalRpm = data.value.globalRpm;
    policy.globalConcurrency = data.value.globalConcurrency;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "仪表盘加载失败"); }
  finally { loading.value = false; }
}
async function savePolicy() {
  try { data.value = await api.updateRuntimePolicy(policy); ElMessage.success("全局网关保护已更新"); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : "更新失败"); }
}
onMounted(load);
</script>

<template>
  <section class="page-stack" v-loading="loading">
    <header class="page-heading"><div><p class="eyebrow">Platform / Gateway-wide overview</p><h1>全网关仪表盘</h1><p>聚合所有团队的网关、额度与账单事实数据；不展示业务 Prompt 或真实 Provider 凭据。</p></div><el-button @click="load">刷新数据</el-button></header>
    <div class="metric-grid" v-if="data"><article><span>启用供应商</span><strong>{{ data.enabledProviderCount }}</strong></article><article><span>启用团队</span><strong>{{ data.enabledTeamCount }}</strong></article><article><span>启用虚拟 Key</span><strong>{{ data.enabledKeyCount }}</strong></article><article class="accent"><span>24h 请求</span><strong>{{ data.requestsLast24Hours }}</strong></article><article><span>24h 成功</span><strong>{{ data.successfulRequestsLast24Hours }}</strong></article><article><span>24h 限流拒绝</span><strong>{{ data.throttledRequestsLast24Hours }}</strong></article><article><span>冻结 Token</span><strong>{{ data.frozenTokens }}</strong></article><article><span>24h 账单</span><strong>{{ data.billingAmountLast24Hours }} {{ data.billingCurrency }}</strong></article></div>
    <section v-if="quotaSummary" class="surface quota-dashboard-surface"><QuotaVisualizationPanel :items="quotaSummary.items" :unlimited-count="quotaSummary.unlimitedEntitlementCount" title="全网关当前周期团队额度" /></section>
    <section class="surface policy-panel"><div><p class="eyebrow">Atomic gateway guardrails</p><h2>全局保护阈值</h2><p>与团队、Key 和模型限制一起由 Redis Lua 原子校验；任一限制命中都不会冻结额度。</p></div><el-form inline @submit.prevent="savePolicy"><el-form-item label="全局 RPM"><el-input-number v-model="policy.globalRpm" :min="1" /></el-form-item><el-form-item label="全局并发"><el-input-number v-model="policy.globalConcurrency" :min="1" /></el-form-item><el-button type="primary" @click="savePolicy">保存保护策略</el-button></el-form></section>
  </section>
</template>
