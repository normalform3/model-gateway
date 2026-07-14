<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import { formatTokenCount } from "./quota";

export interface QuotaVisualItem { modelName: string; quotaMode: string; teamCount?: number; allocatedTokens: number; consumedTokens: number; frozenTokens: number; remainingTokens: number }
export interface UsageTrend { day: string; tokens: number; amount: number; requests: number }
export interface MemberUsageRank { memberId: number; memberName: string; tokens: number; amount: number }
const props = withDefaults(defineProps<{ items: QuotaVisualItem[]; unlimitedCount?: number; trends?: UsageTrend[]; memberRanking?: MemberUsageRank[]; title?: string; compact?: boolean }>(), { unlimitedCount: 0, trends: () => [], memberRanking: () => [], title: "当前周期额度", compact: false });
const allocationEl = ref<HTMLDivElement>(); const modelEl = ref<HTMLDivElement>(); const trendEl = ref<HTMLDivElement>(); const rankingEl = ref<HTMLDivElement>(); const root = ref<HTMLElement>();
let allocationChart: echarts.ECharts | undefined; let modelChart: echarts.ECharts | undefined; let trendChart: echarts.ECharts | undefined; let rankingChart: echarts.ECharts | undefined; let resizeObserver: ResizeObserver | undefined;
const totals = computed(() => props.items.reduce((result, item) => ({ allocated: result.allocated + item.allocatedTokens, consumed: result.consumed + item.consumedTokens, frozen: result.frozen + item.frozenTokens, remaining: result.remaining + item.remainingTokens }), { allocated: 0, consumed: 0, frozen: 0, remaining: 0 }));
const hasFiniteQuota = computed(() => totals.value.allocated > 0);
function initCharts() { if (allocationEl.value) allocationChart = echarts.init(allocationEl.value); if (modelEl.value) modelChart = echarts.init(modelEl.value); if (trendEl.value) trendChart = echarts.init(trendEl.value); if (rankingEl.value) rankingChart = echarts.init(rankingEl.value); draw(); }
function draw() {
  const total = totals.value;
  allocationChart?.setOption({ tooltip: { trigger: "item", valueFormatter: (value: number) => `${formatTokenCount(value)} Token` }, color: ["#1677c8", "#d08a35", "#7bbbe6"], series: [{ type: "pie", radius: ["58%", "78%"], label: { formatter: "{b}\n{d}%" }, data: hasFiniteQuota.value ? [{ name: "已用", value: total.consumed }, { name: "冻结", value: total.frozen }, { name: "剩余", value: total.remaining }] : [{ name: "暂无有限额度", value: 1, itemStyle: { color: "#d9e2eb" } }] }] }, true);
  const labels = props.items.map(item => `${item.modelName}\n${item.quotaMode === "WEEKLY" ? "每周" : "每日"}`);
  modelChart?.setOption({ tooltip: { trigger: "axis", axisPointer: { type: "shadow" }, valueFormatter: (value: number) => `${formatTokenCount(value)} Token` }, legend: { data: ["已分配", "已用"] }, grid: { top: 42, left: 18, right: 18, bottom: 48, containLabel: true }, xAxis: { type: "value", axisLabel: { formatter: (value: number) => formatTokenCount(value) } }, yAxis: { type: "category", data: labels, axisLabel: { fontSize: 11 } }, series: [{ name: "已分配", type: "bar", data: props.items.map(item => item.allocatedTokens), itemStyle: { color: "#9bc8e8" }, barMaxWidth: 18 }, { name: "已用", type: "bar", data: props.items.map(item => item.consumedTokens), itemStyle: { color: "#1677c8" }, barMaxWidth: 18 }] }, true);
  trendChart?.setOption({ tooltip: { trigger: "axis", valueFormatter: (value: number) => `${formatTokenCount(value)} Token` }, grid: { top: 20, left: 18, right: 18, bottom: 28, containLabel: true }, xAxis: { type: "category", boundaryGap: false, data: props.trends.map(item => item.day.slice(5)) }, yAxis: { type: "value", axisLabel: { formatter: (value: number) => formatTokenCount(value) } }, series: [{ type: "line", smooth: true, showSymbol: false, data: props.trends.map(item => item.tokens), areaStyle: { color: "rgba(22,119,200,.16)" }, lineStyle: { color: "#1677c8", width: 3 }, itemStyle: { color: "#1677c8" } }] }, true);
  rankingChart?.setOption({ tooltip: { trigger: "axis", axisPointer: { type: "shadow" }, valueFormatter: (value: number) => `${formatTokenCount(value)} Token` }, grid: { top: 8, left: 18, right: 18, bottom: 26, containLabel: true }, xAxis: { type: "value", axisLabel: { formatter: (value: number) => formatTokenCount(value) } }, yAxis: { type: "category", inverse: true, data: props.memberRanking.map(item => item.memberName) }, series: [{ type: "bar", data: props.memberRanking.map(item => item.tokens), itemStyle: { color: "#21a5a9", borderRadius: [0, 4, 4, 0] }, barMaxWidth: 22 }] }, true);
}
function resize() { allocationChart?.resize(); modelChart?.resize(); trendChart?.resize(); rankingChart?.resize(); }
onMounted(async () => { await nextTick(); initCharts(); if (root.value) { resizeObserver = new ResizeObserver(resize); resizeObserver.observe(root.value); } });
watch(() => [props.items, props.trends, props.memberRanking] as const, () => draw(), { deep: true });
onBeforeUnmount(() => { resizeObserver?.disconnect(); allocationChart?.dispose(); modelChart?.dispose(); trendChart?.dispose(); rankingChart?.dispose(); });
</script>

<template>
  <section ref="root" class="quota-visual-panel">
    <div class="quota-panel-heading"><div><p class="eyebrow">Quota health</p><h2>{{ title }}</h2></div><el-tag v-if="unlimitedCount" type="info">{{ unlimitedCount }} 项不限额度</el-tag></div>
    <div class="quota-health-grid"><article><span>已分配</span><strong>{{ formatTokenCount(totals.allocated) }}</strong></article><article class="used"><span>已用</span><strong>{{ formatTokenCount(totals.consumed) }}</strong></article><article class="frozen"><span>冻结中</span><strong>{{ formatTokenCount(totals.frozen) }}</strong></article><article class="remaining"><span>剩余</span><strong>{{ formatTokenCount(totals.remaining) }}</strong></article></div>
    <div v-if="!compact" class="quota-charts"><div class="quota-chart"><h3>额度构成</h3><div ref="allocationEl" class="chart-canvas" /></div><div class="quota-chart"><h3>模型对比</h3><div ref="modelEl" class="chart-canvas" /></div></div>
    <div v-if="trends.length" class="quota-chart quota-trend"><h3>近 7 日 Token 用量</h3><div ref="trendEl" class="chart-canvas" /></div>
    <div v-if="memberRanking.length" class="quota-chart quota-trend"><h3>成员 Token 用量排名</h3><div ref="rankingEl" class="chart-canvas" /></div>
    <p v-if="!hasFiniteQuota" class="quota-empty">暂无有限周期额度；不限额度会继续记录实际调用用量。</p>
  </section>
</template>
