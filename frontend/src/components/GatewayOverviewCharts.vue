<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import type { GatewayProtectionOverview } from "../api";

const props = defineProps<{ protection: GatewayProtectionOverview }>();
const emit = defineEmits<{ protection: []; billing: [] }>();
const trendRoot = ref<HTMLDivElement>(); const outcomeRoot = ref<HTMLDivElement>();
let trendChart: echarts.ECharts | undefined; let outcomeChart: echarts.ECharts | undefined; let observer: ResizeObserver | undefined;
function draw() {
  if (!trendChart || !outcomeChart) return;
  trendChart.setOption({ animationDuration: 360, tooltip: { trigger: "axis" }, legend: { data: ["请求", "成功", "保护拒绝"] }, grid: { top: 42, left: 20, right: 20, bottom: 30, containLabel: true }, xAxis: { type: "category", boundaryGap: false, data: props.protection.trends.map(item => item.bucket.slice(5)) }, yAxis: { type: "value" }, series: [
    { name: "请求", type: "line", smooth: true, showSymbol: false, data: props.protection.trends.map(item => item.requests), lineStyle: { color: "#176eac", width: 3 }, areaStyle: { color: "rgba(23,110,172,.12)" } },
    { name: "成功", type: "line", smooth: true, showSymbol: false, data: props.protection.trends.map(item => item.successes), lineStyle: { color: "#2f8a6b", width: 2 } },
    { name: "保护拒绝", type: "line", smooth: true, showSymbol: false, data: props.protection.trends.map(item => item.rateLimited + item.concurrencyLimited), lineStyle: { color: "#c98528", width: 2 } }
  ] }, true);
  outcomeChart.setOption({ animationDuration: 360, tooltip: { trigger: "item" }, color: ["#2f8a6b", "#c98528", "#c95d4b"], series: [{ type: "pie", radius: ["52%", "78%"], label: { formatter: "{b}\\n{d}%" }, data: [
    { name: "成功", value: props.protection.successes }, { name: "保护拒绝", value: props.protection.rateLimited + props.protection.concurrencyLimited }, { name: "其他失败", value: props.protection.otherFailures }
  ] }] }, true);
}
onMounted(async () => { await nextTick(); if (trendRoot.value) trendChart = echarts.init(trendRoot.value); if (outcomeRoot.value) outcomeChart = echarts.init(outcomeRoot.value); observer = new ResizeObserver(() => { trendChart?.resize(); outcomeChart?.resize(); }); if (trendRoot.value) observer.observe(trendRoot.value); if (outcomeRoot.value) observer.observe(outcomeRoot.value); draw(); });
watch(() => props.protection, draw, { deep: true });
onBeforeUnmount(() => { observer?.disconnect(); trendChart?.dispose(); outcomeChart?.dispose(); });
</script>

<template>
  <section class="dashboard-chart-grid">
    <article class="surface dashboard-chart"><div class="section-title"><div><p class="eyebrow">Traffic pulse</p><h2>近 24 小时请求走势</h2></div><el-button text type="primary" @click="emit('protection')">查看保护详情</el-button></div><div ref="trendRoot" class="dashboard-chart-canvas" /></article>
    <article class="surface dashboard-chart"><div class="section-title"><div><p class="eyebrow">Request health</p><h2>请求结果构成</h2></div><el-button text type="primary" @click="emit('billing')">查看成本</el-button></div><div ref="outcomeRoot" class="dashboard-chart-canvas compact" /></article>
  </section>
</template>
