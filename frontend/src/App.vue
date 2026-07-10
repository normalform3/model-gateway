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

const STORAGE_KEY = "modelgate-frontend-state";

type StageState = "idle" | "loading" | "success" | "error";
type RoleKey = "platform-admin" | "team-admin" | "developer";
type CapabilityState = "ready" | "placeholder";

interface PersistedState {
  demo: BootstrapDemoResponse | null;
}

interface RoleView {
  key: RoleKey;
  label: string;
  title: string;
  eyebrow: string;
  description: string;
  focus: string;
}

interface CapabilityCard {
  title: string;
  description: string;
  state: CapabilityState;
  adminOnly?: boolean;
}

const roleViews: RoleView[] = [
  {
    key: "platform-admin",
    label: "平台管理员",
    title: "平台控制面",
    eyebrow: "Provider / Pricing / Global guardrails",
    description: "管理模型供应商、真实凭据、企业团队、模型单价、全局限流和平台运行状态。",
    focus: "全局配置与运行治理"
  },
  {
    key: "team-admin",
    label: "团队管理员",
    title: "团队使用者工作台",
    eyebrow: "Projects / Budget / Model access",
    description: "管理团队项目、虚拟 Key、预算、可用模型、团队用量账单和预算告警。",
    focus: "团队成本与访问边界"
  },
  {
    key: "developer",
    label: "开发者",
    title: "开发者调用工作台",
    eyebrow: "Virtual key / Request logs / Errors",
    description: "使用虚拟 Key 调用模型，查看项目调用记录、错误原因和剩余额度。",
    focus: "项目调用与问题排查"
  }
];

const platformCapabilityCards: CapabilityCard[] = [
  {
    title: "接入模型供应商",
    description: "维护 OpenAI-Compatible、Mock Provider 与后续供应商的接入配置。",
    state: "placeholder"
  },
  {
    title: "配置真实 API Key",
    description: "真实 Provider 凭据只应存在于受控环境，前端仅展示配置入口。",
    state: "placeholder"
  },
  {
    title: "配置模型单价",
    description: "为逻辑模型和实际部署维护计费单价，支撑后续账单追溯。",
    state: "placeholder"
  },
  {
    title: "创建企业和团队",
    description: "Demo 初始化已能生成最小组织、团队、应用和额度上下文。",
    state: "ready"
  },
  {
    title: "设置全局限流",
    description: "平台级 RPM、TPM 和并发边界后续接入控制面配置。",
    state: "placeholder"
  },
  {
    title: "查看平台运行状态",
    description: "当前页面先保留请求状态和额度观测，平台健康看板待接入。",
    state: "placeholder"
  }
];

const userCapabilityCards: CapabilityCard[] = [
  {
    title: "创建项目",
    description: "团队管理员后续在这里维护应用、环境和项目归属。",
    state: "placeholder",
    adminOnly: true
  },
  {
    title: "申请虚拟 API Key",
    description: "当前闭环已支持创建虚拟 Key，并且只在创建后展示一次。",
    state: "ready"
  },
  {
    title: "配置团队预算",
    description: "团队级预算策略待接入，当前以额度账户展示可用、冻结和已用 Token。",
    state: "placeholder",
    adminOnly: true
  },
  {
    title: "限制可使用的模型",
    description: "创建 Key 时可填写允许访问的逻辑模型列表。",
    state: "ready",
    adminOnly: true
  },
  {
    title: "查看团队用量和账单",
    description: "团队管理员可查看团队额度和当前应用调用记录，账单页后续补齐。",
    state: "placeholder",
    adminOnly: true
  },
  {
    title: "设置预算告警",
    description: "预算告警是异步计量能力的一部分，当前仅保留入口。",
    state: "placeholder",
    adminOnly: true
  },
  {
    title: "使用虚拟 Key 调用模型",
    description: "支持 JSON 和 SSE 两种调用方式，可直接验证网关主链路。",
    state: "ready"
  },
  {
    title: "查看项目调用记录",
    description: "调用日志表展示请求 ID、模型、Provider、Token、耗时和创建时间。",
    state: "ready"
  },
  {
    title: "查询错误原因",
    description: "失败时展示后端错误码、HTTP 状态和可诊断原因，不暴露敏感信息。",
    state: "ready"
  },
  {
    title: "查看剩余额度",
    description: "额度图表展示可用、冻结和已用 Token。",
    state: "ready"
  }
];

const demo = ref<BootstrapDemoResponse | null>(null);
const quota = ref<QuotaResponse | null>(null);
const logs = ref<RequestLogItem[]>([]);
const keyResult = ref<CreateApiKeyResponse | null>(null);
const apiKey = ref("");
const assistantOutput = ref("");
const lastError = ref("");
const activeRole = ref<RoleKey>("platform-admin");

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

const currentRole = computed(() => roleViews.find((item) => item.key === activeRole.value) ?? roleViews[0]);
const contextReady = computed(() => demo.value !== null);
const keyReady = computed(() => apiKey.value.trim().length > 0);
const logicalModel = computed(() => demo.value?.logicalModel ?? "smart-chat");
const isTeamAdmin = computed(() => activeRole.value === "team-admin");
const isDeveloper = computed(() => activeRole.value === "developer");
const totalQuota = computed(() => {
  if (!quota.value) {
    return 0;
  }
  return quota.value.availableTokens + quota.value.frozenTokens + quota.value.consumedTokens;
});
const successCount = computed(() => logs.value.filter((item) => item.status === "SUCCESS").length);
const failedCount = computed(() => logs.value.filter((item) => item.status !== "SUCCESS").length);
const activeCapabilities = computed(() => {
  if (activeRole.value === "platform-admin") {
    return platformCapabilityCards;
  }
  if (isTeamAdmin.value) {
    return userCapabilityCards;
  }
  return userCapabilityCards.filter((item) => !item.adminOnly);
});
const visibleLogs = computed(() => (isDeveloper.value ? logs.value.slice(0, 8) : logs.value));

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

function selectRole(role: RoleKey): void {
  activeRole.value = role;
  void nextTick(renderCharts);
}

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
    tooltip: {
      trigger: "item",
      backgroundColor: "#fffaf1",
      borderColor: "#d8c7b6",
      textStyle: { color: "#2b2621" }
    },
    color: ["#66806f", "#b9823d", "#b85d4c"],
    series: [
      {
        type: "pie",
        radius: ["58%", "78%"],
        center: ["50%", "52%"],
        avoidLabelOverlap: true,
        label: { color: "#2b2621", formatter: "{b}\n{c}" },
        labelLine: { lineStyle: { color: "#b7a99b" } },
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
    tooltip: {
      trigger: "axis",
      backgroundColor: "#fffaf1",
      borderColor: "#d8c7b6",
      textStyle: { color: "#2b2621" }
    },
    xAxis: {
      type: "category",
      data: ["成功", "失败"],
      axisLine: { lineStyle: { color: "#b7a99b" } },
      axisLabel: { color: "#655b52" }
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      splitLine: { lineStyle: { color: "rgba(98, 78, 61, 0.14)" } },
      axisLabel: { color: "#655b52" }
    },
    series: [
      {
        type: "bar",
        barWidth: 32,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: [
          { value: successCount.value, itemStyle: { color: "#66806f" } },
          { value: failedCount.value, itemStyle: { color: "#b85d4c" } }
        ]
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

      <div class="role-switcher" aria-label="角色切换">
        <button
          v-for="role in roleViews"
          :key="role.key"
          type="button"
          class="role-tab"
          :class="{ active: activeRole === role.key }"
          @click="selectRole(role.key)"
        >
          <span>{{ role.label }}</span>
          <small>{{ role.focus }}</small>
        </button>
      </div>

      <nav class="rail-nav" aria-label="工作台导航">
        <a href="#overview">角色概览</a>
        <a href="#capabilities">职责范围</a>
        <a href="#workspace">MVP 闭环</a>
        <a href="#observability">观测日志</a>
      </nav>

      <div class="rail-footer">
        <span>Backend</span>
        <strong>localhost:8080</strong>
      </div>
    </aside>

    <section class="workspace">
      <header class="hero-strip" id="overview">
        <div class="hero-copy">
          <p class="eyebrow">{{ currentRole.eyebrow }}</p>
          <h1>{{ currentRole.title }}</h1>
          <p>{{ currentRole.description }}</p>
        </div>
        <div class="hero-actions">
          <el-tag effect="plain" class="role-tag">{{ currentRole.label }}</el-tag>
          <el-button
            class="primary-action"
            type="primary"
            :loading="demoState === 'loading'"
            @click="initializeDemo"
          >
            初始化 Demo
          </el-button>
        </div>
      </header>

      <section class="context-grid" aria-label="当前上下文">
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

      <section class="capability-grid" id="capabilities">
        <article
          v-for="item in activeCapabilities"
          :key="`${activeRole}-${item.title}`"
          class="capability-card"
        >
          <div>
            <h2>{{ item.title }}</h2>
            <p>{{ item.description }}</p>
          </div>
          <span class="capability-state" :class="item.state">
            {{ item.state === "ready" ? "当前闭环" : "待接入" }}
          </span>
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

      <section class="main-grid" id="workspace">
        <div class="stack">
          <article class="panel" id="key">
            <div class="panel-head">
              <div>
                <p class="eyebrow">
                  {{ isDeveloper ? "Virtual Key Usage" : "Virtual API Key" }}
                </p>
                <h2>{{ isDeveloper ? "调用凭据" : "创建调用凭据" }}</h2>
              </div>
              <el-tag :type="contextReady ? 'success' : 'info'" effect="plain">
                {{ contextReady ? "Demo ready" : "Need bootstrap" }}
              </el-tag>
            </div>

            <p v-if="isDeveloper" class="placeholder-note">
              开发者通常使用团队管理员发放的虚拟 Key；这里保留创建能力用于本地 MVP 闭环验证。
            </p>

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
                type="primary"
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
                type="primary"
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
                <p class="eyebrow">{{ isTeamAdmin ? "Team quota account" : "Quota account" }}</p>
                <h2>{{ isDeveloper ? "剩余额度" : "团队额度" }}</h2>
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
                <h2>{{ isDeveloper ? "项目调用状态" : "调用状态" }}</h2>
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
            <p class="eyebrow">
              {{ isDeveloper ? "Project requests" : "Application requests" }}
            </p>
            <h2>{{ isDeveloper ? "我的项目调用记录" : "调用日志" }}</h2>
          </div>
          <el-button :disabled="!contextReady" :loading="logsState === 'loading'" @click="refreshLogs">
            刷新日志
          </el-button>
        </div>
        <p v-if="isDeveloper" class="placeholder-note">
          开发者视图聚焦项目排障，只展示最近记录；团队级账单和预算配置由团队管理员查看。
        </p>
        <el-table
          v-if="visibleLogs.length"
          :data="visibleLogs"
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
              <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
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
