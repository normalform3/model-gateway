<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import * as echarts from "echarts";
import { api, type ModelEntitlement, type UsageDashboard } from "../api";
import { formatTokenCount } from "../components/quota";

const props = defineProps<{ memberId?: number | null }>();
const data = ref<UsageDashboard | null>(null);
const loading = ref(false);
const chartRoot = ref<HTMLElement>();
const compositionChartEl = ref<HTMLDivElement>();
const modelUsageChartEl = ref<HTMLDivElement>();
let compositionChart: echarts.ECharts | undefined;
let modelUsageChart: echarts.ECharts | undefined;
let resizeObserver: ResizeObserver | undefined;

const finiteEntitlements = computed(() => data.value?.modelEntitlements.filter(item => item.quotaLimit !== null) ?? []);
const unlimitedEntitlements = computed(() => data.value?.modelEntitlements.filter(item => item.quotaLimit === null) ?? []);
const quotaTotals = computed(() => finiteEntitlements.value.reduce((total, item) => ({
  limit: total.limit + (item.quotaLimit ?? 0),
  consumed: total.consumed + item.consumedTokens,
  frozen: total.frozen + item.frozenTokens,
  remaining: total.remaining + (item.remainingTokens ?? 0)
}), { limit: 0, consumed: 0, frozen: 0, remaining: 0 }));
const consumptionPercent = computed(() => percentage(quotaTotals.value.consumed, quotaTotals.value.limit));
const occupiedPercent = computed(() => percentage(quotaTotals.value.consumed + quotaTotals.value.frozen, quotaTotals.value.limit));
const remainingPercent = computed(() => percentage(quotaTotals.value.remaining, quotaTotals.value.limit));

function percentage(value: number, total: number) { return total > 0 ? Math.min(100, Math.round(value / total * 1000) / 10) : 0; }
function percentLabel(value: number, total: number) { return total > 0 ? `${percentage(value, total).toFixed(1)}%` : "—"; }
function quotaModeLabel(mode: ModelEntitlement["quotaMode"]) { return mode === "DAILY" ? "每日额度" : mode === "WEEKLY" ? "每周额度" : "不限额度"; }
function progressColor(item: ModelEntitlement) {
  const percent = percentage(item.consumedTokens + item.frozenTokens, item.quotaLimit ?? 0);
  if (percent >= 85) return "#c15d4f";
  if (percent >= 65) return "#cb8734";
  return "#1677c8";
}

function drawCharts() {
  const total = quotaTotals.value;
  const hasFiniteQuota = total.limit > 0;
  compositionChart?.setOption({
    color: ["#1776be", "#c98836", "#9dbfd8"],
    tooltip: { trigger: "item", valueFormatter: (value: number) => `${formatTokenCount(value)} Token` },
    graphic: [{ type: "text", left: "center", top: "42%", style: { text: hasFiniteQuota ? `${occupiedPercent.value.toFixed(1)}%\n已占用` : "暂无\n有限额度", textAlign: "center", fill: "#18334a", font: "700 18px Avenir Next" } }],
    series: [{
      type: "pie", radius: ["58%", "80%"], avoidLabelOverlap: true,
      label: { show: false }, labelLine: { show: false },
      data: hasFiniteQuota
        ? [{ name: "已用", value: total.consumed }, { name: "冻结", value: total.frozen }, { name: "剩余", value: total.remaining }]
        : [{ name: "暂无有限额度", value: 1, itemStyle: { color: "#dce8f0" } }]
    }]
  }, true);

  const items = finiteEntitlements.value;
  modelUsageChart?.setOption({
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" }, formatter: (params: Array<{ name: string; value: number }>) => `${params[0].name}<br/>已占用：${params[0].value.toFixed(1)}%` },
    grid: { top: 6, right: 22, bottom: 18, left: 18, containLabel: true },
    xAxis: { type: "value", max: 100, axisLabel: { formatter: "{value}%", color: "#718094" }, splitLine: { lineStyle: { color: "#e5edf3" } } },
    yAxis: { type: "category", inverse: true, data: items.map(item => item.modelName), axisLabel: { color: "#415366", width: 130, overflow: "truncate" }, axisTick: { show: false }, axisLine: { show: false } },
    series: [{
      type: "bar", barMaxWidth: 20, showBackground: true, backgroundStyle: { color: "#edf3f7", borderRadius: 5 },
      label: { show: true, position: "right", formatter: ({ value }: { value: number }) => `${value.toFixed(1)}%`, color: "#52687a", fontSize: 11 },
      data: items.map(item => ({ value: percentage(item.consumedTokens + item.frozenTokens, item.quotaLimit ?? 0), itemStyle: { color: progressColor(item), borderRadius: [0, 5, 5, 0] } }))
    }]
  }, true);
}

function resizeCharts() { compositionChart?.resize(); modelUsageChart?.resize(); }
async function setupCharts() {
  await nextTick();
  if (compositionChartEl.value && !compositionChart) compositionChart = echarts.init(compositionChartEl.value);
  if (modelUsageChartEl.value && !modelUsageChart) modelUsageChart = echarts.init(modelUsageChartEl.value);
  if (chartRoot.value && !resizeObserver) { resizeObserver = new ResizeObserver(resizeCharts); resizeObserver.observe(chartRoot.value); }
  drawCharts();
}
async function load() {
  if (!props.memberId) { data.value = null; return; }
  loading.value = true;
  try { data.value = await api.memberUsageDashboard(props.memberId); await setupCharts(); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : "开发者用量加载失败"); }
  finally { loading.value = false; }
}

watch(() => props.memberId, () => void load());
onMounted(load);
onBeforeUnmount(() => { resizeObserver?.disconnect(); compositionChart?.dispose(); modelUsageChart?.dispose(); });
</script>

<template>
  <section class="page-stack developer-dashboard" v-loading="loading">
    <header class="page-heading">
      <div><p class="eyebrow">Developer / personal usage</p><h1>我的开发者工作台</h1><p>按模型查看当前授权、周期额度与实际调用情况。</p></div>
      <el-button @click="load">刷新</el-button>
    </header>

    <template v-if="data">
      <section ref="chartRoot" class="developer-quota-overview surface">
        <div class="developer-quota-heading"><div><p class="eyebrow">Personal quota health</p><h2>当前周期额度</h2><p>百分比仅统计有限额度；冻结 Token 已计入已占用，避免在途请求造成误判。</p></div><el-tag v-if="unlimitedEntitlements.length" type="info">{{ unlimitedEntitlements.length }} 项不限额度</el-tag></div>
        <div class="developer-metric-grid">
          <article><span>额度上限</span><strong>{{ formatTokenCount(quotaTotals.limit) }}</strong><small>有限额度模型汇总</small></article>
          <article class="is-used"><span>已用占比</span><strong>{{ consumptionPercent.toFixed(1) }}%</strong><small>{{ formatTokenCount(quotaTotals.consumed) }} 已消费</small></article>
          <article class="is-occupied"><span>已占用</span><strong>{{ occupiedPercent.toFixed(1) }}%</strong><small>已用 + {{ formatTokenCount(quotaTotals.frozen) }} 冻结</small></article>
          <article class="is-remaining"><span>剩余额度</span><strong>{{ remainingPercent.toFixed(1) }}%</strong><small>{{ formatTokenCount(quotaTotals.remaining) }} 可用</small></article>
        </div>
        <div class="developer-chart-grid">
          <article class="developer-chart"><div class="chart-title"><h3>额度构成</h3><span>当前周期</span></div><div ref="compositionChartEl" class="developer-chart-canvas" /></article>
          <article class="developer-chart"><div class="chart-title"><h3>模型占用率</h3><span>已用 + 冻结</span></div><div ref="modelUsageChartEl" class="developer-chart-canvas" /></article>
        </div>
      </section>

      <section class="developer-model-section surface">
        <div class="section-title"><div><p class="eyebrow">Model entitlements</p><h2>模型额度明细</h2></div><span class="muted">按当前成员已授权模型展示</span></div>
        <div v-if="data.modelEntitlements.length" class="developer-model-grid">
          <article v-for="item in data.modelEntitlements" :key="item.grantId" class="developer-model-card">
            <div class="developer-model-card-heading"><div><strong>{{ item.modelName }}</strong><span>{{ quotaModeLabel(item.quotaMode) }}</span></div><b v-if="item.quotaLimit !== null">{{ percentLabel(item.consumedTokens + item.frozenTokens, item.quotaLimit) }}</b><b v-else>不限</b></div>
            <el-progress v-if="item.quotaLimit !== null" :percentage="percentage(item.consumedTokens + item.frozenTokens, item.quotaLimit)" :stroke-width="8" :show-text="false" :color="progressColor(item)" />
            <p v-if="item.quotaLimit !== null"><span>已用 {{ formatTokenCount(item.consumedTokens) }}</span><span>冻结 {{ formatTokenCount(item.frozenTokens) }}</span></p>
            <div class="developer-model-card-total"><span>{{ item.quotaLimit === null ? '实际用量持续记录' : `剩余 ${formatTokenCount(item.remainingTokens)}` }}</span><strong>{{ item.quotaLimit === null ? '不限额度' : `上限 ${formatTokenCount(item.quotaLimit)}` }}</strong></div>
          </article>
        </div>
        <p v-else class="muted">暂未获得模型额度或调用授权。</p>
      </section>

      <section class="surface developer-usage-list">
        <div class="section-title"><div><p class="eyebrow">Usage ledger</p><h2>近 7 日用量</h2><p class="muted">保留列表视图，便于逐日核对调用、Token 和成本。</p></div></div>
        <el-table :data="data.lastSevenDays" size="small" empty-text="近 7 日暂无用量记录"><el-table-column prop="day" label="日期"/><el-table-column prop="tokens" label="Token"/><el-table-column prop="amount" label="成本"/><el-table-column prop="requests" label="请求数"/></el-table>
      </section>
    </template>

    <section v-else class="surface empty-state"><h2>请选择开发成员</h2><p>负责人也可在此作为开发者查看自己的模型权益。</p></section>
  </section>
</template>

<style scoped>
.developer-quota-overview { overflow:hidden; padding:0; border-top:3px solid #176eac; }
.developer-quota-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:18px; padding:22px 24px 18px; background:linear-gradient(118deg,#f2f9fe 0%,#fff 58%,#f3faf8 100%); }
.developer-quota-heading h2 { margin:0; font-size:21px; }.developer-quota-heading p:not(.eyebrow) { max-width:650px; margin:8px 0 0; color:#718094; font-size:13px; line-height:1.6; }
.developer-metric-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; padding:18px 24px; border-top:1px solid #e0ebf2; }.developer-metric-grid article { min-height:112px; padding:15px; border:1px solid #dce7ef; border-radius:10px; background:#fff; }.developer-metric-grid article.is-used { border-top:3px solid #1776be; }.developer-metric-grid article.is-occupied { border-top:3px solid #c98836; }.developer-metric-grid article.is-remaining { border-top:3px solid #6ba5cb; }.developer-metric-grid span,.developer-metric-grid small { display:block; color:#718094; font-size:12px; }.developer-metric-grid strong { display:block; margin:9px 0 7px; color:#182f45; font-size:22px; letter-spacing:-.6px; }.developer-chart-grid { display:grid; grid-template-columns:minmax(240px,.85fr) minmax(330px,1.15fr); gap:14px; padding:0 24px 24px; }.developer-chart { min-width:0; padding:15px; border:1px solid #dce7ef; border-radius:10px; background:#fbfdff; }.chart-title { display:flex; align-items:baseline; justify-content:space-between; gap:12px; }.chart-title h3 { margin:0; font-size:14px; }.chart-title span { color:#718094; font-size:11px; }.developer-chart-canvas { width:100%; height:245px; }
.developer-model-section { padding:22px; }.developer-model-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(245px,1fr)); gap:12px; }.developer-model-card { padding:15px; border:1px solid #dce7ef; border-radius:11px; background:linear-gradient(145deg,#fff,#f7fbfe); }.developer-model-card-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin-bottom:14px; }.developer-model-card-heading strong,.developer-model-card-heading span { display:block; }.developer-model-card-heading strong { color:#1b344a; font-size:15px; overflow-wrap:anywhere; }.developer-model-card-heading span { margin-top:4px; color:#718094; font-size:11px; }.developer-model-card-heading b { color:#176eac; font-size:16px; white-space:nowrap; }.developer-model-card p { display:flex; justify-content:space-between; gap:8px; margin:10px 0 14px; color:#718094; font-size:11px; }.developer-model-card-total { display:flex; justify-content:space-between; gap:12px; padding-top:11px; border-top:1px solid #e4edf3; color:#718094; font-size:11px; }.developer-model-card-total strong { color:#3b5266; font-size:11px; font-weight:700; text-align:right; }.developer-usage-list { padding:22px; }
@media (max-width:900px) { .developer-metric-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }.developer-chart-grid { grid-template-columns:1fr; }.developer-chart-canvas { height:230px; } }
@media (max-width:520px) { .developer-quota-heading { padding:18px; flex-direction:column; }.developer-metric-grid,.developer-chart-grid { padding-left:18px; padding-right:18px; }.developer-metric-grid { grid-template-columns:1fr; }.developer-model-section,.developer-usage-list { padding:18px; }.developer-model-card p,.developer-model-card-total { flex-direction:column; gap:5px; }.developer-model-card-total strong { text-align:left; } }
</style>
