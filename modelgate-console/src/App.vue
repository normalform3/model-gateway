<script setup lang="ts">
import * as echarts from "echarts";
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  ApiError,
  bootstrapDemo,
  completeChat,
  createApiKey,
  fetchQuota,
  fetchRequestLogs,
  streamChat,
  type BootstrapDemoResponse,
  type ChatCompletionRequest,
  type CreateApiKeyResponse,
  type QuotaResponse,
  type RequestLogItem
} from "./api";

const STORAGE_KEY = "modelgate-console-state";

type StageState = "idle" | "loading" | "success" | "error";

interface PersistedState {
  demo: BootstrapDemoResponse | null;
}

const demo = ref<BootstrapDemoResponse | null>(null);
const quota = ref<QuotaResponse | null>(null);
const logs = ref<RequestLogItem[]>([]);
const keyResult = ref<CreateApiKeyResponse | null>(null);
const apiKey = ref("");
const assistantOutput = ref("");
const lastError = ref("");

const demoState = ref<StageState>("idle");
const keyState = ref<StageState>("idle");
const quotaState = ref<StageState>("idle");
const logsState = ref<StageState>("idle");
const chatState = ref<StageState>("idle");

const keyForm = reactive({
  name: "codereader-dev",
  allowedModels: "smart-chat",
  expiresAt: ""
});

const chatForm = reactive({
  prompt: "解释 ModelGate 的网关、额度和计量链路。",
  stream: true,
  maxTokens: 128
});

const quotaChartRef = ref<HTMLDivElement | null>(null);
const statusChartRef = ref<HTMLDivElement | null>(null);
let quotaChart: echarts.ECharts | null = null;
let statusChart: echarts.ECharts | null = null;

const contextReady = computed(() => demo.value !== null);
const keyReady = computed(() => apiKey.value.trim().length > 0);
const logicalModel = computed(() => demo.value?.logicalModel ?? "smart-chat");
const totalQuota = computed(() => {
  if (!quota.value) {
    return 0;
  }
  return quota.value.availableTokens + quota.value.frozenTokens + quota.value.consumedTokens;
});
const successCount = computed(() => logs.value.filter((item) => item.status === "SUCCESS").length);
const failedCount = computed(() => logs.value.filter((item) => item.status !== "SUCCESS").length);

onMounted(() => {
  restoreState();
  window.addEventListener("resize", resizeCharts);
  void nextTick(renderCharts);
});

onUnmounted(() => {
  window.removeEventListener("resize", resizeCharts);
  quotaChart?.dispose();
  statusChart?.dispose();
});

watch([quota, logs], () => {
  void nextTick(renderCharts);
});

watch(demo, persistState, { deep: true });

async function initializeDemo(): Promise<void> {
  demoState.value = "loading";
  lastError.value = "";
  try {
    demo.value = await bootstrapDemo();
    keyForm.allowedModels = demo.value.logicalModel;
    demoState.value = "success";
    ElMessage.success("Demo 上下文已初始化");
    await Promise.all([refreshQuota(), refreshLogs()]);
  } catch (error) {
    demoState.value = "error";
    showError(error, "初始化 Demo 失败");
  }
}

async function submitApiKey(): Promise<void> {
  if (!demo.value) {
    ElMessage.warning("请先初始化 Demo 上下文");
    return;
  }

  keyState.value = "loading";
  lastError.value = "";
  try {
    const allowedModels = keyForm.allowedModels
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean);
    keyResult.value = await createApiKey({
      organizationId: demo.value.organizationId,
      teamId: demo.value.teamId,
      applicationId: demo.value.applicationId,
      name: keyForm.name,
      allowedModels,
      expiresAt: keyForm.expiresAt || null
    });
    apiKey.value = keyResult.value.apiKey;
    keyState.value = "success";
    ElMessage.success("虚拟 Key 已创建，请在离开页面前保存");
  } catch (error) {
    keyState.value = "error";
    showError(error, "创建虚拟 Key 失败");
  }
}

async function copyApiKey(): Promise<void> {
  if (!keyResult.value?.apiKey) {
    return;
  }
  await navigator.clipboard.writeText(keyResult.value.apiKey);
  ElMessage.success("Key 已复制");
}

async function refreshQuota(): Promise<void> {
  if (!demo.value) {
    return;
  }
  quotaState.value = "loading";
  try {
    quota.value = await fetchQuota(demo.value.teamId);
    quotaState.value = "success";
  } catch (error) {
    quotaState.value = "error";
    showError(error, "刷新额度失败");
  }
}

async function refreshLogs(): Promise<void> {
  if (!demo.value) {
    return;
  }
  logsState.value = "loading";
  try {
    const response = await fetchRequestLogs(demo.value.applicationId);
    logs.value = response.items;
    logsState.value = "success";
  } catch (error) {
    logsState.value = "error";
    showError(error, "刷新调用日志失败");
  }
}

async function runChat(): Promise<void> {
  if (!keyReady.value) {
    ElMessage.warning("请先创建或粘贴虚拟 Key");
    return;
  }

  assistantOutput.value = "";
  chatState.value = "loading";
  lastError.value = "";

  const payload: ChatCompletionRequest = {
    model: logicalModel.value,
    messages: [{ role: "user", content: chatForm.prompt }],
    stream: chatForm.stream,
    max_tokens: chatForm.maxTokens
  };

  try {
    if (chatForm.stream) {
      await streamChat(apiKey.value.trim(), payload, (content) => {
        assistantOutput.value += content;
      });
    } else {
      const response = await completeChat(apiKey.value.trim(), payload);
      assistantOutput.value = response.choices[0]?.message?.content ?? "";
    }
    chatState.value = "success";
    await Promise.all([refreshQuota(), refreshLogs()]);
  } catch (error) {
    chatState.value = "error";
    showError(error, "模型调用失败");
  }
}

function renderCharts(): void {
  renderQuotaChart();
  renderStatusChart();
}

function renderQuotaChart(): void {
  if (!quotaChartRef.value) {
    return;
  }
  quotaChart ??= echarts.init(quotaChartRef.value);
  const current = quota.value;
  quotaChart.setOption({
    tooltip: { trigger: "item" },
    color: ["#35d6a4", "#f4bf4f", "#e8664f"],
    series: [
      {
        type: "pie",
        radius: ["58%", "78%"],
        center: ["50%", "52%"],
        avoidLabelOverlap: true,
        label: { color: "#dce5df", formatter: "{b}\n{c}" },
        labelLine: { lineStyle: { color: "#6a776f" } },
        data: current
          ? [
              { value: current.availableTokens, name: "可用" },
              { value: current.frozenTokens, name: "冻结" },
              { value: current.consumedTokens, name: "已用" }
            ]
          : [{ value: 1, name: "未加载" }]
      }
    ]
  });
}

function renderStatusChart(): void {
  if (!statusChartRef.value) {
    return;
  }
  statusChart ??= echarts.init(statusChartRef.value);
  statusChart.setOption({
    grid: { left: 24, right: 12, top: 24, bottom: 24 },
    tooltip: { trigger: "axis" },
    xAxis: {
      type: "category",
      data: ["成功", "失败"],
      axisLine: { lineStyle: { color: "#59665f" } },
      axisLabel: { color: "#cfd8d2" }
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      splitLine: { lineStyle: { color: "rgba(255,255,255,0.08)" } },
      axisLabel: { color: "#cfd8d2" }
    },
    series: [
      {
        type: "bar",
        barWidth: 32,
        itemStyle: { borderRadius: [3, 3, 0, 0], color: "#35d6a4" },
        data: [successCount.value, failedCount.value]
      }
    ]
  });
}

function resizeCharts(): void {
  quotaChart?.resize();
  statusChart?.resize();
}

function restoreState(): void {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return;
  }
  try {
    const state = JSON.parse(raw) as PersistedState;
    demo.value = state.demo;
    if (state.demo?.logicalModel) {
      keyForm.allowedModels = state.demo.logicalModel;
    }
    if (state.demo) {
      void Promise.all([refreshQuota(), refreshLogs()]);
    }
  } catch {
    localStorage.removeItem(STORAGE_KEY);
  }
}

function persistState(): void {
  const state: PersistedState = {
    demo: demo.value
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

function showError(error: unknown, fallback: string): void {
  const message = formatError(error, fallback);
  lastError.value = message;
  ElMessage.error(message);
}

function formatError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const code = error.code ? ` ${error.code}` : "";
    return `${fallback}: HTTP ${error.status}${code} - ${error.message}`;
  }
  if (error instanceof Error) {
    return `${fallback}: ${error.message}`;
  }
  return fallback;
}

function formatNumber(value: number | null | undefined): string {
  return value == null ? "-" : new Intl.NumberFormat("zh-CN").format(value);
}

function formatTime(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date(value));
}
</script>

<template>
  <main class="console-shell">
    <aside class="rail">
      <div class="brand-block">
        <span class="brand-mark">MG</span>
        <div>
          <strong>ModelGate</strong>
          <small>AI Gateway Console</small>
        </div>
      </div>
      <nav class="rail-nav" aria-label="工作台导航">
        <a href="#context">上下文</a>
        <a href="#key">虚拟 Key</a>
        <a href="#call">调用测试</a>
        <a href="#observability">观测</a>
      </nav>
      <div class="rail-footer">
        <span>Backend</span>
        <strong>localhost:8080</strong>
      </div>
    </aside>

    <section class="workspace">
      <header class="hero-strip" id="context">
        <div>
          <p class="eyebrow">Control plane / Data plane / Usage ledger</p>
          <h1>企业 AI 网关运维工作台</h1>
        </div>
        <el-button
          class="primary-action"
          type="success"
          :loading="demoState === 'loading'"
          @click="initializeDemo"
        >
          初始化 Demo
        </el-button>
      </header>

      <section class="context-grid">
        <article class="metric-tile">
          <span>Organization</span>
          <strong>{{ demo?.organizationId ?? "-" }}</strong>
        </article>
        <article class="metric-tile">
          <span>Team</span>
          <strong>{{ demo?.teamId ?? "-" }}</strong>
        </article>
        <article class="metric-tile">
          <span>Application</span>
          <strong>{{ demo?.applicationId ?? "-" }}</strong>
        </article>
        <article class="metric-tile accent">
          <span>Logical model</span>
          <strong>{{ logicalModel }}</strong>
        </article>
      </section>

      <el-alert
        v-if="lastError"
        class="surface-alert"
        :title="lastError"
        type="error"
        show-icon
        :closable="false"
      />

      <section class="main-grid">
        <div class="stack">
          <article class="panel" id="key">
            <div class="panel-head">
              <div>
                <p class="eyebrow">Virtual API Key</p>
                <h2>创建调用凭据</h2>
              </div>
              <el-tag :type="contextReady ? 'success' : 'info'" effect="dark">
                {{ contextReady ? "Demo ready" : "Need bootstrap" }}
              </el-tag>
            </div>

            <el-form label-position="top" class="dense-form">
              <el-form-item label="Key 名称">
                <el-input v-model="keyForm.name" :disabled="!contextReady" />
              </el-form-item>
              <el-form-item label="允许模型">
                <el-input v-model="keyForm.allowedModels" :disabled="!contextReady" />
              </el-form-item>
              <el-form-item label="过期时间">
                <el-input
                  v-model="keyForm.expiresAt"
                  placeholder="可留空，例如 2026-12-31T23:59:59+08:00"
                  :disabled="!contextReady"
                />
              </el-form-item>
            </el-form>

            <div class="panel-actions">
              <el-button
                type="success"
                :disabled="!contextReady"
                :loading="keyState === 'loading'"
                @click="submitApiKey"
              >
                创建虚拟 Key
              </el-button>
            </div>

            <div v-if="keyResult" class="key-reveal">
              <div>
                <span>Key ID #{{ keyResult.keyId }} / {{ keyResult.keyPrefix }}</span>
                <code>{{ keyResult.apiKey }}</code>
              </div>
              <el-button @click="copyApiKey">复制</el-button>
            </div>
            <el-empty v-else class="compact-empty" description="Key 只会在创建后展示一次" />
          </article>

          <article class="panel" id="call">
            <div class="panel-head">
              <div>
                <p class="eyebrow">Chat completions</p>
                <h2>模型调用测试</h2>
              </div>
              <el-switch v-model="chatForm.stream" active-text="SSE" inactive-text="JSON" />
            </div>

            <el-form label-position="top" class="dense-form">
              <el-form-item label="虚拟 Key">
                <el-input
                  v-model="apiKey"
                  type="password"
                  show-password
                  placeholder="创建后自动填入，也可以手动粘贴"
                />
              </el-form-item>
              <el-form-item label="Prompt">
                <el-input v-model="chatForm.prompt" type="textarea" :rows="4" />
              </el-form-item>
              <el-form-item label="Max tokens">
                <el-input-number v-model="chatForm.maxTokens" :min="16" :max="2048" :step="16" />
              </el-form-item>
            </el-form>

            <div class="panel-actions">
              <el-button
                type="success"
                :disabled="!keyReady"
                :loading="chatState === 'loading'"
                @click="runChat"
              >
                发起调用
              </el-button>
            </div>

            <pre v-if="assistantOutput" class="response-console">{{ assistantOutput }}</pre>
            <el-empty v-else class="compact-empty" description="响应会显示在这里" />
          </article>
        </div>

        <div class="stack" id="observability">
          <article class="panel chart-panel">
            <div class="panel-head">
              <div>
                <p class="eyebrow">Quota account</p>
                <h2>团队额度</h2>
              </div>
              <el-button :disabled="!contextReady" :loading="quotaState === 'loading'" @click="refreshQuota">
                刷新
              </el-button>
            </div>
            <div class="quota-summary">
              <span>总额度</span>
              <strong>{{ formatNumber(totalQuota) }}</strong>
              <small>更新时间 {{ formatTime(quota?.updatedAt) }}</small>
            </div>
            <div ref="quotaChartRef" class="chart-box" />
          </article>

          <article class="panel chart-panel">
            <div class="panel-head">
              <div>
                <p class="eyebrow">Request state</p>
                <h2>调用状态</h2>
              </div>
              <el-button :disabled="!contextReady" :loading="logsState === 'loading'" @click="refreshLogs">
                刷新
              </el-button>
            </div>
            <div ref="statusChartRef" class="chart-box short" />
          </article>
        </div>
      </section>

      <section class="panel log-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">Application requests</p>
            <h2>调用日志</h2>
          </div>
          <el-button :disabled="!contextReady" :loading="logsState === 'loading'" @click="refreshLogs">
            刷新日志
          </el-button>
        </div>
        <el-table
          v-if="logs.length"
          :data="logs"
          class="request-table"
          height="360"
          row-key="requestId"
        >
          <el-table-column prop="requestId" label="Request ID" min-width="220" />
          <el-table-column prop="requestedModel" label="逻辑模型" width="120" />
          <el-table-column prop="actualProvider" label="Provider" width="110" />
          <el-table-column prop="actualModel" label="实际模型" width="120" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" effect="dark">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Token" width="120">
            <template #default="{ row }">{{ row.inputTokens }} / {{ row.outputTokens }}</template>
          </el-table-column>
          <el-table-column prop="durationMs" label="耗时 ms" width="100" />
          <el-table-column label="首 Token ms" width="120">
            <template #default="{ row }">{{ row.firstTokenMs ?? "-" }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="150">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else class="log-empty" description="暂无调用记录" />
      </section>
    </section>
  </main>
</template>
