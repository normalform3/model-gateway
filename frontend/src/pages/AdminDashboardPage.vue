<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import GatewayOverviewCharts from "../components/GatewayOverviewCharts.vue";
import GatewayProtectionPanel from "../components/GatewayProtectionPanel.vue";
import QuotaVisualizationPanel from "../components/QuotaVisualizationPanel.vue";
import { formatTokenCount } from "../components/quota";
import { api, type DashboardOverview, type GatewayProtectionOverview, type QuotaSummary } from "../api";

type DashboardTab = "overview" | "quota" | "protection";
const emit = defineEmits<{ openBilling: [] }>();
const activeTab = ref<DashboardTab>("overview");
const overview = ref<DashboardOverview | null>(null);
const quotaSummary = ref<QuotaSummary | null>(null);
const protection = ref<GatewayProtectionOverview | null>(null);
const loading = ref(false);
const quotaPool = ref<"DEVELOPMENT" | "APPLICATION">("DEVELOPMENT");
const protectionRange = ref<"24h" | "7d">("24h");
const policy = reactive({ globalRpm: 10000, globalConcurrency: 1000 });
const successRate = computed(() => !overview.value?.requestsLast24Hours ? "—" : `${((overview.value.successfulRequestsLast24Hours / overview.value.requestsLast24Hours) * 100).toFixed(1)}%`);
const guardRate = computed(() => !overview.value?.requestsLast24Hours ? "—" : `${((overview.value.throttledRequestsLast24Hours / overview.value.requestsLast24Hours) * 100).toFixed(1)}%`);
const rpmPercent = computed(() => !protection.value?.rpm.limit ? 0 : Math.min(100, Math.round(protection.value.rpm.current / protection.value.rpm.limit * 100)));
const concurrencyPercent = computed(() => !protection.value?.concurrency.limit ? 0 : Math.min(100, Math.round(protection.value.concurrency.current / protection.value.concurrency.limit * 100)));

function formatAmounts() { return overview.value?.billingAmountsLast24Hours.length ? overview.value.billingAmountsLast24Hours.map(item => `${item.currency} ${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 4 }).format(item.amount)}`).join(" · ") : "暂无账单"; }
async function loadOverview() { const [nextOverview, nextProtection] = await Promise.all([api.dashboard(), api.gatewayProtection("24h")]); overview.value = nextOverview; protection.value = nextProtection; policy.globalRpm = nextOverview.globalRpm; policy.globalConcurrency = nextOverview.globalConcurrency; }
async function loadQuota() { quotaSummary.value = await api.quotaSummary(quotaPool.value); }
async function loadProtection() { protection.value = await api.gatewayProtection(protectionRange.value); if (overview.value) { policy.globalRpm = overview.value.globalRpm; policy.globalConcurrency = overview.value.globalConcurrency; } }
async function reload() { loading.value = true; try { if (activeTab.value === "overview") await loadOverview(); if (activeTab.value === "quota") await loadQuota(); if (activeTab.value === "protection") await loadProtection(); } catch (error) { ElMessage.error(error instanceof Error ? error.message : "仪表盘加载失败"); } finally { loading.value = false; } }
async function savePolicy() { try { const updated = await api.updateRuntimePolicy(policy); overview.value = updated; policy.globalRpm = updated.globalRpm; policy.globalConcurrency = updated.globalConcurrency; await loadProtection(); ElMessage.success("全局保护阈值已更新"); } catch (error) { ElMessage.error(error instanceof Error ? error.message : "更新失败"); } }
function changeTab(tab: string | number) { activeTab.value = tab as DashboardTab; void reload(); }
watch(quotaPool, () => { if (activeTab.value === "quota") void reload(); });
watch(protectionRange, () => { if (activeTab.value === "protection") void reload(); });
onMounted(reload);
</script>

<template>
  <section class="page-stack dashboard-page" v-loading="loading">
    <header class="page-heading"><div><p class="eyebrow">Platform / operation cockpit</p><h1>全网关仪表盘</h1><p>从请求健康、周期额度到实时保护压力，集中观察全网关运行状态；不展示业务 Prompt 或真实 Provider 凭据。</p></div><el-button @click="reload">刷新当前视图</el-button></header>
    <el-tabs v-model="activeTab" class="dashboard-tabs" @tab-change="changeTab"><el-tab-pane label="概览" name="overview" /><el-tab-pane label="额度" name="quota" /><el-tab-pane label="并发与限流" name="protection" /></el-tabs>

    <template v-if="activeTab === 'overview' && overview">
      <section class="dashboard-metric-groups"><div class="dashboard-metric-group"><p>平台资产</p><div class="metric-grid"><article><span>启用供应商</span><strong>{{ overview.enabledProviderCount }}</strong></article><article><span>启用团队</span><strong>{{ overview.enabledTeamCount }}</strong></article><article><span>启用虚拟 Key</span><strong>{{ overview.enabledKeyCount }}</strong></article></div></div><div class="dashboard-metric-group"><p>近 24 小时请求健康</p><div class="metric-grid"><article class="accent"><span>请求总量</span><strong>{{ overview.requestsLast24Hours }}</strong></article><article><span>成功率</span><strong>{{ successRate }}</strong><small>{{ overview.successfulRequestsLast24Hours }} 次成功</small></article><article><span>保护拒绝率</span><strong>{{ guardRate }}</strong><small>{{ overview.throttledRequestsLast24Hours }} 次拒绝</small></article><article><span>非保护失败</span><strong>{{ overview.failedRequestsLast24Hours }}</strong><small>不含取消和保护拒绝</small></article></div></div></section>
      <GatewayOverviewCharts v-if="protection" :protection="protection" @protection="activeTab = 'protection'; reload()" @billing="emit('openBilling')" />
      <section class="surface dashboard-cost-strip"><div><p class="eyebrow">Cost pulse</p><h2>近 24 小时成本</h2><p>按原始币种展示，未进行汇率换算。</p></div><strong>{{ formatAmounts() }}</strong><el-button text type="primary" @click="emit('openBilling')">进入账单分析</el-button></section>
    </template>

    <template v-if="activeTab === 'quota' && quotaSummary">
      <section class="surface dashboard-subheader"><div><p class="eyebrow">Quota health</p><h2>网关额度</h2><p>开发调用与项目应用额度池分别汇总团队级权益，下级成员与项目分配不重复计入。</p></div><el-radio-group v-model="quotaPool"><el-radio-button label="DEVELOPMENT">开发调用</el-radio-button><el-radio-button label="APPLICATION">项目应用</el-radio-button></el-radio-group></section>
      <section class="surface quota-dashboard-surface"><QuotaVisualizationPanel :items="quotaSummary.items" :unlimited-count="quotaSummary.unlimitedEntitlementCount" :title="quotaPool === 'DEVELOPMENT' ? '开发调用当前周期额度' : '项目应用当前周期额度'" /></section>
      <section class="surface dashboard-alerts"><div class="section-title"><div><p class="eyebrow">Threshold crossings</p><h2>额度预警</h2></div><span class="muted">最多展示最近 20 条</span></div><el-table :data="quotaSummary.alerts" empty-text="当前额度池没有触发阈值的预警"><el-table-column prop="teamName" label="团队" min-width="140" /><el-table-column prop="modelName" label="模型" min-width="160" /><el-table-column label="剩余 / 上限" min-width="180"><template #default="scope">{{ formatTokenCount(scope.row.remainingTokens) }} / {{ formatTokenCount(scope.row.quotaLimit) }}</template></el-table-column><el-table-column label="阈值" width="100"><template #default="scope"><el-tag type="warning">{{ scope.row.alertRemainingPercent }}%</el-tag></template></el-table-column><el-table-column label="触发时间" min-width="170"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</template></el-table-column></el-table></section>
    </template>

    <template v-if="activeTab === 'protection' && protection">
      <section class="surface dashboard-subheader protection-subheader"><div><p class="eyebrow">Live gateway guardrails</p><h2>并发与限流</h2><p>实时压力读取全局 Redis Key；历史趋势来自请求事实。团队级策略继续在团队管理中维护。</p></div><el-radio-group v-model="protectionRange"><el-radio-button label="24h">近 24 小时</el-radio-button><el-radio-button label="7d">近 7 天</el-radio-button></el-radio-group></section>
      <section class="protection-pressure-grid"><article class="surface pressure-card"><div><span>全局 RPM</span><strong>{{ protection.rpm.current }} <small>/ {{ protection.rpm.limit }}</small></strong></div><el-progress :percentage="rpmPercent" :stroke-width="12" :color="rpmPercent >= 85 ? '#c95d4b' : '#176eac'" /><small>当前一分钟已接受的请求</small></article><article class="surface pressure-card"><div><span>全局并发</span><strong>{{ protection.concurrency.current }} <small>/ {{ protection.concurrency.limit }}</small></strong></div><el-progress :percentage="concurrencyPercent" :stroke-width="12" :color="concurrencyPercent >= 85 ? '#c95d4b' : '#2f8a6b'" /><small>含尚未完成或超时前的请求</small></article><article class="surface pressure-card rejection"><span>当前范围保护拒绝</span><strong>{{ protection.rateLimited + protection.concurrencyLimited }}</strong><small>RPM / TPM {{ protection.rateLimited }} · 并发 {{ protection.concurrencyLimited }}</small></article></section>
      <GatewayProtectionPanel :data="protection" />
      <section class="surface policy-panel dashboard-policy"><div><p class="eyebrow">Global controls</p><h2>全局保护阈值</h2><p>修改后会在网关配置缓存刷新后参与同一 Redis Lua 原子校验。</p></div><el-form inline @submit.prevent="savePolicy"><el-form-item label="全局 RPM"><el-input-number v-model="policy.globalRpm" :min="1" /></el-form-item><el-form-item label="全局并发"><el-input-number v-model="policy.globalConcurrency" :min="1" /></el-form-item><el-button type="primary" @click="savePolicy">保存保护策略</el-button></el-form></section>
    </template>
  </section>
</template>
