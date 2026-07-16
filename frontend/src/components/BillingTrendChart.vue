<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import type { BillingDailyTrend } from "../api";

const props = defineProps<{ trends: BillingDailyTrend[]; currency: string | null }>();
const root = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;
let observer: ResizeObserver | undefined;

function amountFor(item: BillingDailyTrend) {
  return props.currency ? item.amounts.find(amount => amount.currency === props.currency)?.amount ?? 0 : 0;
}

function draw() {
  if (!chart) return;
  const showAmount = Boolean(props.currency);
  chart.setOption({
    animationDuration: 360,
    tooltip: { trigger: "axis" },
    legend: { data: showAmount ? ["Token", `费用 (${props.currency})`] : ["Token"] },
    grid: { top: 42, left: 22, right: showAmount ? 42 : 20, bottom: 30, containLabel: true },
    xAxis: { type: "category", boundaryGap: false, data: props.trends.map(item => item.day.slice(5)) },
    yAxis: showAmount
      ? [{ type: "value", name: "Token" }, { type: "value", name: props.currency ?? "" }]
      : [{ type: "value", name: "Token" }],
    series: [
      { name: "Token", type: "line", smooth: true, showSymbol: false, data: props.trends.map(item => item.totalTokens), areaStyle: { color: "rgba(23, 110, 172, .14)" }, lineStyle: { color: "#176eac", width: 3 }, itemStyle: { color: "#176eac" } },
      ...(showAmount ? [{ name: `费用 (${props.currency})`, type: "line", yAxisIndex: 1, smooth: true, showSymbol: false, data: props.trends.map(amountFor), lineStyle: { color: "#c98528", width: 2 }, itemStyle: { color: "#c98528" } }] : [])
    ]
  }, true);
}

onMounted(async () => {
  await nextTick();
  if (!root.value) return;
  chart = echarts.init(root.value);
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(root.value);
  draw();
});
watch(() => [props.trends, props.currency] as const, draw, { deep: true });
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose(); });
</script>

<template><div ref="root" class="billing-trend-chart" /></template>
