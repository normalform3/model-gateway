<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import BillingTrendChart from "../components/BillingTrendChart.vue";
import { api, type BillingDimensionItem, type BillingOverview, type BillingRecordItem, type DirectModel, type ProjectItem, type TeamMemberItem, type TeamSummary } from "../api";

type DateRange = [string, string];
const overview = ref<BillingOverview | null>(null);
const records = ref<BillingRecordItem[]>([]);
const teams = ref<TeamSummary[]>([]);
const projects = ref<ProjectItem[]>([]);
const members = ref<TeamMemberItem[]>([]);
const models = ref<DirectModel[]>([]);
const loading = ref(false);
const recordsLoading = ref(false);
const total = ref(0);
const page = ref(0);
const pageSize = ref(20);

function iso(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`; }
function thisMonth(): DateRange { const now = new Date(); return [iso(new Date(now.getFullYear(), now.getMonth(), 1)), iso(now)]; }
function recent(days: number): DateRange { const today = new Date(); const from = new Date(today); from.setDate(today.getDate() - days + 1); return [iso(from), iso(today)]; }
const filters = reactive<{ range: DateRange; teamId: number | null; projectId: number | null; memberId: number | null; provider: string | null; model: string | null; credentialType: string | null; currency: string | null }>({
  range: thisMonth(), teamId: null, projectId: null, memberId: null, provider: null, model: null, credentialType: null, currency: null
});

const filteredModels = computed(() => models.value.filter(item => !filters.provider || item.providerName === filters.provider));
const currencies = computed(() => overview.value?.amounts.map(item => item.currency) ?? []);

function query() {
  return { from: filters.range[0], to: filters.range[1], teamId: filters.teamId, projectId: filters.projectId, memberId: filters.memberId,
    provider: filters.provider, model: filters.model, credentialType: filters.credentialType, currency: filters.currency };
}
function token(value: number) { return new Intl.NumberFormat("zh-CN", { notation: "compact", maximumFractionDigits: 2 }).format(value); }
function amount(items: { currency: string; amount: number }[]) { return items.length ? items.map(item => `${item.currency} ${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 4 }).format(item.amount)}`).join(" · ") : "—"; }
function typeLabel(type: string) { return type === "APPLICATION" ? "项目应用" : "成员开发"; }

async function load(resetPage = true) {
  if (resetPage) page.value = 0;
  loading.value = true; recordsLoading.value = true;
  try {
    const currentQuery = { ...query(), page: page.value, size: pageSize.value };
    const [nextOverview, nextRecords] = await Promise.all([api.billingOverview(currentQuery), api.billingRecords(currentQuery)]);
    overview.value = nextOverview; records.value = nextRecords.items; total.value = nextRecords.total;
    if (filters.currency && !nextOverview.amounts.some(item => item.currency === filters.currency)) filters.currency = null;
    if (!filters.currency && nextOverview.amounts.length === 1) filters.currency = nextOverview.amounts[0].currency;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "账单数据加载失败"); }
  finally { loading.value = false; recordsLoading.value = false; }
}

async function loadScopeOptions() {
  try {
    const [teamResponse, modelResponse] = await Promise.all([api.teams({ size: 100 }), api.directModels()]);
    teams.value = teamResponse.items; models.value = modelResponse.items;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "账单筛选项加载失败"); }
}

async function loadTeamChildren() {
  projects.value = []; members.value = [];
  if (!filters.teamId) return;
  try {
    const [projectResponse, memberResponse] = await Promise.all([api.projects(filters.teamId), api.members(filters.teamId)]);
    projects.value = projectResponse.items; members.value = memberResponse.items;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "团队账单筛选项加载失败"); }
}
async function onTeamChanged() { filters.projectId = null; filters.memberId = null; await loadTeamChildren(); }
function preset(range: DateRange) { filters.range = range; void load(); }
function reset() { Object.assign(filters, { range: thisMonth(), teamId: null, projectId: null, memberId: null, provider: null, model: null, credentialType: null, currency: null }); void load(); }
function drill(kind: "team" | "project" | "member" | "model", item: BillingDimensionItem) {
  if (kind === "team") { filters.teamId = item.id; void loadTeamChildren(); }
  if (kind === "project") { filters.teamId = item.teamId; filters.projectId = item.id; void loadTeamChildren(); }
  if (kind === "member") { filters.teamId = item.teamId; filters.memberId = item.id; void loadTeamChildren(); }
  if (kind === "model") { filters.provider = item.provider; filters.model = item.model; }
  void load();
}
function changePage(nextPage: number) { page.value = nextPage - 1; void load(false); }
function changePageSize(nextSize: number) { pageSize.value = nextSize; void load(); }

watch(() => filters.provider, () => { if (filters.model && !filteredModels.value.some(item => item.modelName === filters.model)) filters.model = null; });
onMounted(async () => { await loadScopeOptions(); await load(); });
</script>

<template>
  <section class="page-stack billing-page">
    <header class="page-heading"><div><p class="eyebrow">Platform / cost intelligence</p><h1>账单分析</h1><p>按相同账单事实查看平台、团队、项目、成员和实际模型成本；各视图用于下钻，不能跨视图相加。</p></div><el-button :loading="loading" @click="load">刷新数据</el-button></header>

    <section class="surface billing-filter-panel">
      <div class="billing-filter-heading"><strong>分析范围</strong><small>自然日按 Asia/Shanghai 计算；最多 366 天。</small></div>
      <div class="billing-presets"><el-button size="small" @click="preset(recent(1))">本日</el-button><el-button size="small" @click="preset(recent(7))">近 7 日</el-button><el-button size="small" @click="preset(thisMonth())">本月</el-button></div>
      <div class="billing-filters">
        <el-date-picker v-model="filters.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
        <el-select v-model="filters.teamId" clearable placeholder="全部团队" @change="onTeamChanged"><el-option v-for="team in teams" :key="team.teamId" :label="team.name" :value="team.teamId" /></el-select>
        <el-select v-model="filters.projectId" clearable :disabled="!filters.teamId" placeholder="全部项目"><el-option v-for="project in projects" :key="project.projectId" :label="project.name" :value="project.projectId" /></el-select>
        <el-select v-model="filters.memberId" clearable :disabled="!filters.teamId" placeholder="全部成员"><el-option v-for="member in members" :key="member.memberId" :label="member.name" :value="member.memberId" /></el-select>
        <el-select v-model="filters.provider" clearable placeholder="全部供应商"><el-option v-for="provider in [...new Set(models.map(item => item.providerName))]" :key="provider" :label="provider" :value="provider" /></el-select>
        <el-select v-model="filters.model" clearable placeholder="全部实际模型"><el-option v-for="model in filteredModels" :key="model.modelId" :label="model.modelName" :value="model.modelName" /></el-select>
        <el-select v-model="filters.credentialType" clearable placeholder="全部调用类型"><el-option label="成员开发" value="DEVELOPER" /><el-option label="项目应用" value="APPLICATION" /></el-select>
        <el-select v-model="filters.currency" clearable placeholder="全部币种"><el-option v-for="currency in currencies" :key="currency" :label="currency" :value="currency" /></el-select>
        <div class="billing-filter-actions"><el-button type="primary" @click="load">应用筛选</el-button><el-button @click="reset">重置</el-button></div>
      </div>
    </section>

    <template v-if="overview">
      <div class="metric-grid billing-metric-grid"><article class="accent"><span>分析 Token</span><strong>{{ token(overview.totalTokens) }}</strong><small>{{ overview.from }} 至 {{ overview.to }}</small></article><article><span>账单记录</span><strong>{{ overview.recordCount }}</strong><small>每条请求费用事实仅计入一次</small></article><article v-for="item in overview.amounts" :key="item.currency"><span>{{ item.currency }} 成本</span><strong>{{ amount([item]) }}</strong><small>未做跨币种换算</small></article></div>
      <section class="surface billing-trend-surface"><div class="section-title"><div><p class="eyebrow">Usage trend</p><h2>Token 与费用趋势</h2></div><el-tag v-if="!filters.currency && overview.amounts.length > 1" type="warning">请选择币种以叠加费用趋势</el-tag></div><BillingTrendChart :trends="overview.dailyTrends" :currency="filters.currency" /></section>
      <section class="billing-rank-grid">
        <article v-for="group in [{ key: 'team', label: '团队成本归因', items: overview.teams }, { key: 'project', label: '项目应用成本', items: overview.projects }, { key: 'member', label: '成员开发成本', items: overview.members }, { key: 'model', label: '供应商 / 实际模型', items: overview.models }]" :key="group.key" class="surface billing-ranking"><div class="section-title"><h2>{{ group.label }}</h2><span class="muted">按 Token 排序 · 点击下钻</span></div><el-table :data="group.items" size="small" empty-text="当前范围暂无账单" @row-click="(item: BillingDimensionItem) => drill(group.key as 'team' | 'project' | 'member' | 'model', item)"><el-table-column prop="label" label="归因对象" min-width="160" /><el-table-column label="Token" width="100" align="right"><template #default="scope">{{ token(scope.row.totalTokens) }}</template></el-table-column><el-table-column label="费用（按币种）" min-width="150"><template #default="scope">{{ amount(scope.row.amounts) }}</template></el-table-column></el-table></article>
      </section>
    </template>

    <section class="surface billing-records"><div class="section-title"><div><p class="eyebrow">Ledger facts</p><h2>账单明细</h2></div><span class="muted">{{ total }} 条记录</span></div><el-table :data="records" v-loading="recordsLoading" empty-text="当前筛选范围没有账单记录"><el-table-column label="时间" min-width="168"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString("zh-CN", { hour12: false }) }}</template></el-table-column><el-table-column prop="teamName" label="团队" min-width="120" /><el-table-column prop="projectName" label="项目" min-width="120" /><el-table-column prop="memberName" label="成员" min-width="110" /><el-table-column label="调用类型" width="100"><template #default="scope"><el-tag size="small" :type="scope.row.credentialType === 'APPLICATION' ? 'success' : 'info'">{{ typeLabel(scope.row.credentialType) }}</el-tag></template></el-table-column><el-table-column label="实际模型" min-width="170"><template #default="scope"><strong>{{ scope.row.model }}</strong><span class="table-subline">{{ scope.row.provider }}</span></template></el-table-column><el-table-column label="输入 / 输出" min-width="130" align="right"><template #default="scope">{{ token(scope.row.inputTokens) }} / {{ token(scope.row.outputTokens) }}</template></el-table-column><el-table-column label="金额" min-width="130" align="right"><template #default="scope">{{ scope.row.currency }} {{ scope.row.amount }}</template></el-table-column></el-table><el-pagination class="table-pagination" background layout="total, sizes, prev, pager, next" :current-page="page + 1" :page-size="pageSize" :page-sizes="[20, 50, 100]" :total="total" @current-change="changePage" @size-change="changePageSize" /></section>
  </section>
</template>
