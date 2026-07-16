<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import QuotaAmountInput from "../components/QuotaAmountInput.vue";
import QuotaVisualizationPanel from "../components/QuotaVisualizationPanel.vue";
import { defaultQuota, formatTokenCount, type QuotaScope } from "../components/quota";
import { api, type ApplicationQuotaOverview, type ModelEntitlement, type PlatformUser, type ProjectApplicationQuotaOverview, type ProjectItem, type ProjectServiceAccountStatus, type TeamMemberCandidate, type TeamMemberItem, type TeamSummary, type UsageDashboard } from "../api";

type Dialog = "team" | "edit" | "owner" | "team-entitlement" | "member-entitlement" | "member" | "application-pool" | "project" | "project-allocation" | "service-account" | null;
type TeamView = "cards" | "table";
type DirectoryStatus = "ALL" | "RUNNING" | TeamSummary["status"];
type TeamDetail = { members: TeamMemberItem[]; entitlements: ModelEntitlement[]; dashboard: UsageDashboard };

const props = defineProps<{ ownerUserId?: number | null; ownerMemberId?: number | null; adminMode: boolean }>();
const activeTeams = ref<TeamSummary[]>([]);
const directoryTeams = ref<TeamSummary[]>([]);
const selected = ref<TeamSummary | null>(null);
const members = ref<TeamMemberItem[]>([]);
const users = ref<PlatformUser[]>([]);
const allModels = ref<string[]>([]);
const entitlements = ref<ModelEntitlement[]>([]);
const dashboard = ref<UsageDashboard | null>(null);
const applicationQuota = ref<ApplicationQuotaOverview | null>(null);
const projects = ref<ProjectItem[]>([]);
const selectedProject = ref<ProjectItem | null>(null);
const projectQuota = ref<ProjectApplicationQuotaOverview | null>(null);
const serviceAccounts = ref<ProjectServiceAccountStatus[]>([]);
const applicationLoading = ref(false);
const projectLoading = ref(false);
const selectedMember = ref<TeamMemberItem | null>(null);
const memberEntitlements = ref<ModelEntitlement[]>([]);
const candidates = ref<TeamMemberCandidate[]>([]);
const candidateId = ref<number | null>(null);
const catalogLoading = ref(false);
const directoryLoading = ref(false);
const detailLoading = ref(false);
const detailVisible = ref(false);
const quotaInputValid = ref(true);
const activeTab = ref("overview");
const viewMode = ref<TeamView>("cards");
const ownerUserId = ref<number | null>(null);
const directoryLoaded = ref(false);
const directoryKeyword = ref("");
const directoryStatus = ref<DirectoryStatus>("ALL");
const directoryOwner = ref<number | "ALL" | "UNASSIGNED">("ALL");
const directoryPage = ref(1);
const directorySize = ref(20);
const directoryTotal = ref(0);
const detailCache = new Map<number, TeamDetail>();
let detailRequestId = 0;

const selectedIsOperational = computed(() => Boolean(selected.value?.enabled && selected.value.status !== "DISSOLVED"));
const ownerMember = computed(() => members.value.find(member => member.memberId === props.ownerMemberId) ?? null);
const hasActiveOwner = computed(() => selectedIsOperational.value && selected.value?.ownerMemberId != null);
const canManageMembers = computed(() => hasActiveOwner.value && (props.adminMode || ownerMember.value?.role === "OWNER"));
const currentOwnerUser = computed(() => selected.value ? users.value.find(user => user.teamId === selected.value?.teamId && user.role === "OWNER") ?? null : null);
const ownerCandidates = computed(() => selected.value ? users.value.filter(user => user.enabled && (user.teamId === null || user.teamId === selected.value?.teamId)) : []);
const directoryOwnerOptions = computed(() => users.value.filter(user => user.enabled && user.role === "OWNER" && user.teamId !== null));
const teamForm = reactive({ organizationId: 1, name: "", ownerUserId: null as number | null, keyRpm: 60, teamRpm: 600, teamConcurrency: 20, modelConcurrency: 50 });
const entitlementForm = reactive({ modelName: "", quotaMode: "DAILY", quotaLimit: 1_000_000_000 as number | null, reason: "" });
const applicationPoolForm = reactive({ tokenAllocation: 1_000_000_000 as number | null, modelNames: [] as string[], reason: "" });
const projectForm = reactive({ name: "", projectCode: "" });
const projectAllocationForm = reactive({ tokenAllocation: 1_000_000_000 as number | null, modelNames: [] as string[], reason: "" });
const serviceAccountForm = reactive({ name: "" });
const dialog = ref<Dialog>(null);
const entitlementScope = computed<QuotaScope>(() => dialog.value === "member-entitlement" ? "MEMBER" : "TEAM");
const quotaItems = computed(() => entitlements.value.filter(item => item.status === "ACTIVE" && item.quotaLimit !== null).map(item => ({ modelName: item.modelName, quotaMode: item.quotaMode, allocatedTokens: item.quotaLimit ?? 0, consumedTokens: item.consumedTokens, frozenTokens: item.frozenTokens, remainingTokens: item.remainingTokens ?? 0 })));
const unlimitedCount = computed(() => entitlements.value.filter(item => item.status === "ACTIVE" && item.quotaLimit === null).length);
const canManageProjectPool = computed(() => !props.adminMode && canManageMembers.value);
const applicationModels = computed(() => applicationQuota.value?.modelEntitlements.filter(item => item.status === "ACTIVE").map(item => item.modelName) ?? []);
const projectQuotaItems = computed(() => projectQuota.value?.modelEntitlements.filter(item => item.quotaLimit !== null).map(item => ({ modelName: item.modelName, quotaMode: item.quotaMode, allocatedTokens: item.quotaLimit ?? 0, consumedTokens: item.consumedTokens, frozenTokens: item.frozenTokens, remainingTokens: item.remainingTokens ?? 0 })) ?? []);

function teamStatusLabel(status: TeamSummary["status"]) {
  return ({ DRAFT: "草稿", READY_FOR_REQUEST: "待开通", ACTIVE: "运行中", SUSPENDED: "已暂停", DISSOLVED: "已解散" })[status];
}

function knownTeam(teamId: number) {
  return activeTeams.value.find(team => team.teamId === teamId) ?? directoryTeams.value.find(team => team.teamId === teamId) ?? null;
}

function applyDetail(detail: TeamDetail) {
  members.value = detail.members;
  entitlements.value = detail.entitlements;
  dashboard.value = detail.dashboard;
  detailVisible.value = true;
}

function clearDetail() {
  detailRequestId += 1;
  members.value = [];
  entitlements.value = [];
  dashboard.value = null;
  selectedMember.value = null;
  memberEntitlements.value = [];
  applicationQuota.value = null;
  projects.value = [];
  selectedProject.value = null;
  projectQuota.value = null;
  serviceAccounts.value = [];
  applicationLoading.value = false;
  projectLoading.value = false;
  detailVisible.value = false;
  detailLoading.value = false;
}

async function loadCatalog(selectFallback = true) {
  catalogLoading.value = true;
  try {
    const [teamData, modelData, userData] = await Promise.all([
      api.teams({ page: 0, size: 100, ownerUserId: props.ownerUserId ?? null, enabled: true }),
      api.directModels(),
      api.users()
    ]);
    activeTeams.value = teamData.items;
    allModels.value = modelData.items.filter(model => model.enabled).map(model => model.modelName);
    users.value = userData.items;
    if (selected.value) selected.value = knownTeam(selected.value.teamId) ?? selected.value;
    if (selectFallback) {
      const next = selected.value && selected.value.enabled ? knownTeam(selected.value.teamId) : activeTeams.value[0] ?? null;
      if (next) void select(next); else { selected.value = null; clearDetail(); }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "团队加载失败");
  } finally {
    catalogLoading.value = false;
  }
}

async function loadDirectory() {
  if (!props.adminMode) return;
  directoryLoading.value = true;
  try {
    const query: Record<string, string | number | boolean | null> = {
      keyword: directoryKeyword.value.trim() || null,
      page: directoryPage.value - 1,
      size: directorySize.value
    };
    if (directoryStatus.value === "RUNNING") query.enabled = true;
    else if (directoryStatus.value !== "ALL") query.status = directoryStatus.value;
    if (directoryOwner.value === "UNASSIGNED") query.ownerAssigned = false;
    else if (typeof directoryOwner.value === "number") query.ownerUserId = directoryOwner.value;
    const response = await api.teams(query);
    directoryTeams.value = response.items;
    directoryTotal.value = response.total;
    directoryLoaded.value = true;
    if (selected.value) selected.value = knownTeam(selected.value.teamId) ?? selected.value;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "团队目录加载失败");
  } finally {
    directoryLoading.value = false;
  }
}

async function select(team: TeamSummary) {
  selected.value = team;
  selectedMember.value = null;
  memberEntitlements.value = [];
  activeTab.value = "overview";
  applicationQuota.value = null;
  projects.value = [];
  selectedProject.value = null;
  projectQuota.value = null;
  serviceAccounts.value = [];
  const cached = detailCache.get(team.teamId);
  if (cached) applyDetail(cached);
  else {
    members.value = [];
    entitlements.value = [];
    dashboard.value = null;
    detailVisible.value = false;
  }
  const requestId = ++detailRequestId;
  detailLoading.value = true;
  try {
    const [memberData, grantData, dashboardData] = await Promise.all([
      api.members(team.teamId),
      api.teamModelEntitlements(team.teamId),
      api.teamUsageDashboard(team.teamId)
    ]);
    if (requestId !== detailRequestId || selected.value?.teamId !== team.teamId) return;
    const detail = { members: memberData.items, entitlements: grantData.items, dashboard: dashboardData };
    detailCache.set(team.teamId, detail);
    applyDetail(detail);
  } catch (error) {
    if (requestId === detailRequestId) ElMessage.error(error instanceof Error ? error.message : "团队详情加载失败");
  } finally {
    if (requestId === detailRequestId) detailLoading.value = false;
  }
}

async function loadApplicationWorkspace(team = selected.value) {
  if (!team || props.adminMode || !canManageProjectPool.value) return;
  applicationLoading.value = true;
  try {
    const [quotaData, projectData] = await Promise.all([api.teamApplicationQuota(team.teamId), api.projects(team.teamId)]);
    if (selected.value?.teamId !== team.teamId) return;
    applicationQuota.value = quotaData;
    projects.value = projectData.items;
    const next = selectedProject.value && projectData.items.find(item => item.projectId === selectedProject.value?.projectId)
      ? projectData.items.find(item => item.projectId === selectedProject.value?.projectId) ?? null
      : projectData.items[0] ?? null;
    selectedProject.value = next;
    if (next) await selectProject(next);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "项目额度池加载失败");
  } finally {
    applicationLoading.value = false;
  }
}

async function selectProject(project: ProjectItem) {
  if (!selected.value || !canManageProjectPool.value) return;
  selectedProject.value = project;
  projectLoading.value = true;
  try {
    const [quotaData, serviceData] = await Promise.all([
      api.projectApplicationQuota(selected.value.teamId, project.projectId),
      api.projectServiceAccounts(selected.value.teamId, project.projectId)
    ]);
    if (selectedProject.value?.projectId !== project.projectId) return;
    projectQuota.value = quotaData;
    serviceAccounts.value = serviceData.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "项目详情加载失败");
  } finally {
    projectLoading.value = false;
  }
}

function openApplicationPool() {
  Object.assign(applicationPoolForm, { tokenAllocation: 1_000_000_000, modelNames: [], reason: "" });
  dialog.value = "application-pool";
}

async function saveApplicationPool() {
  if (!selected.value || !applicationPoolForm.tokenAllocation || applicationPoolForm.tokenAllocation <= 0 || !applicationPoolForm.modelNames.length) {
    ElMessage.warning("请选择模型并填写有效的额度"); return;
  }
  try {
    await api.grantTeamApplicationPool(selected.value.teamId, applicationPoolForm);
    dialog.value = null;
    ElMessage.success("团队项目额度池已更新");
    if (!props.adminMode) await loadApplicationWorkspace();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "项目额度池配置失败"); }
}

function openProjectDialog() {
  Object.assign(projectForm, { name: "", projectCode: "" });
  dialog.value = "project";
}

async function saveProject() {
  if (!selected.value || !canManageProjectPool.value || !projectForm.name.trim() || !projectForm.projectCode.trim()) {
    ElMessage.warning("请填写项目名称和项目编码"); return;
  }
  try {
    const created = await api.createProject(selected.value.teamId, projectForm);
    dialog.value = null;
    await loadApplicationWorkspace();
    const next = projects.value.find(item => item.projectId === created.projectId);
    if (next) await selectProject(next);
    ElMessage.success("项目已创建");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "创建项目失败"); }
}

function openProjectAllocation() {
  Object.assign(projectAllocationForm, { tokenAllocation: 1_000_000_000, modelNames: applicationModels.value.slice(0, 1), reason: "" });
  dialog.value = "project-allocation";
}

async function saveProjectAllocation() {
  if (!selected.value || !selectedProject.value || !props.ownerMemberId || !projectAllocationForm.tokenAllocation || projectAllocationForm.tokenAllocation <= 0 || !projectAllocationForm.modelNames.length) {
    ElMessage.warning("请选择模型并填写有效的额度"); return;
  }
  try {
    await api.allocateProjectQuota(selected.value.teamId, selectedProject.value.projectId, { ...projectAllocationForm, ownerMemberId: props.ownerMemberId });
    dialog.value = null;
    await loadApplicationWorkspace();
    ElMessage.success("项目额度已划拨");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "项目额度划拨失败"); }
}

async function disableProject(project: ProjectItem) {
  if (!selected.value || !canManageProjectPool.value || !project.enabled) return;
  try {
    await ElMessageBox.confirm(`停用“${project.name}”会阻止其应用凭证继续调用模型，历史用量与账单会保留。`, "确认停用项目", { type: "warning", confirmButtonText: "停用项目" });
    await api.updateProject(selected.value.teamId, project.projectId, { enabled: false });
    await loadApplicationWorkspace();
    ElMessage.success("项目已停用");
  } catch (error) { if (error !== "cancel" && error !== "close") ElMessage.error(error instanceof Error ? error.message : "停用项目失败"); }
}

function openServiceAccountDialog() {
  serviceAccountForm.name = "";
  dialog.value = "service-account";
}

async function saveServiceAccount() {
  if (!selected.value || !selectedProject.value || !serviceAccountForm.name.trim()) { ElMessage.warning("请填写服务账号名称"); return; }
  try {
    await api.createProjectServiceAccount(selected.value.teamId, selectedProject.value.projectId, serviceAccountForm);
    dialog.value = null;
    await selectProject(selectedProject.value);
    ElMessage.success("服务账号已创建");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "创建服务账号失败"); }
}

async function updateServiceAccount(account: ProjectServiceAccountStatus, enabled: boolean) {
  if (!selected.value || !selectedProject.value) return;
  try {
    if (!enabled) await ElMessageBox.confirm(`停用“${account.name}”会立即停用其应用虚拟 Key。`, "确认停用服务账号", { type: "warning", confirmButtonText: "停用服务账号" });
    await api.updateProjectServiceAccount(selected.value.teamId, selectedProject.value.projectId, account.serviceAccountId, { enabled });
    await selectProject(selectedProject.value);
    ElMessage.success(enabled ? "服务账号已启用" : "服务账号已停用");
  } catch (error) { if (error !== "cancel" && error !== "close") ElMessage.error(error instanceof Error ? error.message : "服务账号更新失败"); }
}

async function createApplicationKey(account: ProjectServiceAccountStatus, rotate = false) {
  try {
    if (rotate) await ElMessageBox.confirm("旧 Key 会立即失效，新 Key 只展示一次。", "确认轮换", { type: "warning" });
    const result = rotate ? await api.rotateApplicationKey(account.serviceAccountId) : await api.generateApplicationKey(account.serviceAccountId);
    await ElMessageBox.alert(`<p>请立即安全保存，关闭后无法再次查看。</p><code>${result.apiKey}</code>`, "项目应用虚拟 API Key", { dangerouslyUseHTMLString: true, confirmButtonText: "我已保存" });
    if (selectedProject.value) await selectProject(selectedProject.value);
  } catch (error) { if (error !== "cancel" && error !== "close") ElMessage.error(error instanceof Error ? error.message : "应用 Key 操作失败"); }
}

async function switchView(mode: TeamView) {
  viewMode.value = mode;
  if (mode === "table" && !directoryLoaded.value) await loadDirectory();
}

async function reloadAfterMutation(preferredId: number | null = selected.value?.teamId ?? null) {
  detailCache.clear();
  await loadCatalog(false);
  if (props.adminMode && directoryLoaded.value) await loadDirectory();
  const next = preferredId === null ? null : knownTeam(preferredId);
  if (next) await select(next);
  else if (activeTeams.value[0]) await select(activeTeams.value[0]);
  else { selected.value = null; clearDetail(); }
}

async function runTeamAction(action: "edit" | "dissolve", team: TeamSummary) {
  if (action === "dissolve") { await dissolveTeam(team); return; }
  void select(team);
  openEdit(team);
}

function openCreateTeam() {
  Object.assign(teamForm, { organizationId: 1, name: "", ownerUserId: null, keyRpm: 60, teamRpm: 600, teamConcurrency: 20, modelConcurrency: 50 });
  dialog.value = "team";
}

async function selectMember(member: TeamMemberItem) {
  if (!selected.value || !selectedIsOperational.value || !member.enabled) return;
  selectedMember.value = member;
  try {
    memberEntitlements.value = (await api.memberModelEntitlements(selected.value.teamId, member.memberId)).items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "成员权益加载失败");
  }
}

async function saveTeam() {
  try {
    const created = await api.createTeam(teamForm);
    dialog.value = null;
    await reloadAfterMutation(created.teamId);
    ElMessage.success("团队已创建");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "创建失败"); }
}

function openEdit(team = selected.value) {
  if (!team || !team.enabled || team.status === "DISSOLVED") return;
  Object.assign(teamForm, { organizationId: team.organizationId, name: team.name, ownerUserId: null, keyRpm: team.keyRpm, teamRpm: team.teamRpm, teamConcurrency: team.teamConcurrency, modelConcurrency: team.modelConcurrency });
  dialog.value = "edit";
}

async function saveEdit() {
  if (!selected.value || !selectedIsOperational.value) return;
  try {
    await api.updateTeam(selected.value.teamId, teamForm);
    dialog.value = null;
    await reloadAfterMutation(selected.value.teamId);
    ElMessage.success("团队已更新");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "更新失败"); }
}

function openOwnerDialog() {
  if (!props.adminMode || !selected.value || !selectedIsOperational.value) return;
  ownerUserId.value = currentOwnerUser.value?.userId ?? null;
  dialog.value = "owner";
}

async function saveOwner() {
  if (!selected.value || !selectedIsOperational.value || !ownerUserId.value) { ElMessage.warning("请选择新的团队负责人"); return; }
  try {
    await api.setTeamOwner(selected.value.teamId, ownerUserId.value);
    dialog.value = null;
    await reloadAfterMutation(selected.value.teamId);
    ElMessage.success("团队负责人已更新");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "负责人变更失败"); }
}

async function dissolveTeam(team: TeamSummary) {
  if (!team.enabled) return;
  try {
    await ElMessageBox.confirm(`解散“${team.name}”会立即停用成员和全部虚拟 Key。调用、用量、账单、权益和额度历史会保留，且不能在控制台恢复。`, "确认解散团队", { type: "warning", confirmButtonText: "解散团队", cancelButtonText: "取消" });
    await api.dissolveTeam(team.teamId);
    await reloadAfterMutation(selected.value?.teamId === team.teamId ? null : selected.value?.teamId ?? null);
    ElMessage.success("团队已解散，历史数据已保留");
  } catch (error) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error instanceof Error ? error.message : "解散团队失败");
  }
}

function openEntitlement(member?: TeamMemberItem, current?: ModelEntitlement) {
  if (!selectedIsOperational.value) return;
  const scope: QuotaScope = member ? "MEMBER" : "TEAM";
  selectedMember.value = member ?? null;
  const mode = current?.quotaMode ?? "DAILY";
  Object.assign(entitlementForm, { modelName: current?.modelName ?? entitlements.value.find(item => item.status === "ACTIVE")?.modelName ?? allModels.value[0] ?? "", quotaMode: mode, quotaLimit: current?.quotaLimit ?? defaultQuota(scope, mode), reason: current?.reason ?? "" });
  quotaInputValid.value = true;
  dialog.value = member ? "member-entitlement" : "team-entitlement";
}

function quotaModeChanged() {
  if (entitlementForm.quotaMode === "UNLIMITED") { entitlementForm.quotaLimit = null; quotaInputValid.value = true; }
  else if (entitlementForm.quotaLimit === null) entitlementForm.quotaLimit = defaultQuota(entitlementScope.value, entitlementForm.quotaMode);
}

async function saveEntitlement() {
  if (!selected.value || !selectedIsOperational.value || !quotaInputValid.value || (entitlementForm.quotaMode !== "UNLIMITED" && entitlementForm.quotaLimit === null)) { ElMessage.warning("请先填写有效的额度"); return; }
  const payload = { quotaMode: entitlementForm.quotaMode, quotaLimit: entitlementForm.quotaLimit, reason: entitlementForm.reason, ownerMemberId: props.ownerMemberId };
  try {
    if (dialog.value === "team-entitlement") await api.upsertTeamModelEntitlement(selected.value.teamId, entitlementForm.modelName, payload);
    else if (selectedMember.value) await api.upsertMemberModelEntitlement(selected.value.teamId, selectedMember.value.memberId, entitlementForm.modelName, payload);
    const memberId = selectedMember.value?.memberId ?? null;
    dialog.value = null;
    detailCache.delete(selected.value.teamId);
    await select(selected.value);
    if (memberId) {
      const member = members.value.find(item => item.memberId === memberId);
      if (member) await selectMember(member);
    }
    ElMessage.success("模型权益已保存");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "权益保存失败"); }
}

async function revokeTeam(item: ModelEntitlement) {
  if (!selected.value || !selectedIsOperational.value) return;
  try {
    await ElMessageBox.confirm(`收回 ${item.modelName} 的团队权限和额度？`, "确认收回", { type: "warning" });
    await api.revokeTeamModelEntitlement(selected.value.teamId, item.modelName);
    detailCache.delete(selected.value.teamId);
    await select(selected.value);
    ElMessage.success("团队权益已收回");
  } catch (error) { if (error !== "cancel") ElMessage.error(error instanceof Error ? error.message : "收回失败"); }
}

async function revokeMember(item: ModelEntitlement) {
  if (!selected.value || !selectedIsOperational.value || !selectedMember.value || !props.ownerMemberId) return;
  try {
    const memberId = selectedMember.value.memberId;
    await ElMessageBox.confirm(`收回 ${selectedMember.value.name} 的 ${item.modelName} 权益？`, "确认收回", { type: "warning" });
    await api.revokeMemberModelEntitlement(selected.value.teamId, memberId, item.modelName, props.ownerMemberId);
    detailCache.delete(selected.value.teamId);
    await select(selected.value);
    const member = members.value.find(current => current.memberId === memberId);
    if (member) await selectMember(member);
    ElMessage.success("成员权益已收回");
  } catch (error) { if (error !== "cancel") ElMessage.error(error instanceof Error ? error.message : "收回失败"); }
}

async function openMemberDialog() {
  if (!selected.value || !selectedIsOperational.value || !canManageMembers.value) return;
  try {
    candidates.value = (await api.memberCandidates(selected.value.teamId)).items;
    candidateId.value = null;
    dialog.value = "member";
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "成员候选列表加载失败"); }
}

async function saveMember() {
  if (!selected.value || !selectedIsOperational.value || !candidateId.value) { ElMessage.warning("请选择要加入的用户"); return; }
  try {
    if (props.adminMode) await api.addPlatformMember(selected.value.teamId, candidateId.value);
    else if (props.ownerMemberId) await api.addExistingMember(selected.value.teamId, props.ownerMemberId, candidateId.value);
    else return;
    dialog.value = null;
    activeTab.value = "members";
    await reloadAfterMutation(selected.value.teamId);
    ElMessage.success("成员已加入团队");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : "添加成员失败"); }
}

async function deactivateMember(member: TeamMemberItem) {
  if (!selected.value || !selectedIsOperational.value || member.role === "OWNER" || !canManageMembers.value) return;
  try {
    await ElMessageBox.confirm(`移出 ${member.name} 将立即停用其 API Key 并收回当前模型权益；历史调用和账单仍会保留。`, "确认移出成员", { type: "warning", confirmButtonText: "移出并停用" });
    if (props.adminMode) await api.deactivateMember(selected.value.teamId, member.memberId);
    else if (props.ownerMemberId) await api.deactivateOwnedMember(selected.value.teamId, member.memberId, props.ownerMemberId);
    else return;
    await reloadAfterMutation(selected.value.teamId);
    ElMessage.success("成员已移出，Key 和当前权益已停用");
  } catch (error) { if (error !== "cancel" && error !== "close") ElMessage.error(error instanceof Error ? error.message : "移出成员失败"); }
}

async function resetDirectory() {
  directoryPage.value = 1;
  await loadDirectory();
}

async function changeDirectoryPage(page: number) {
  directoryPage.value = page;
  await loadDirectory();
}

function handleTabChange(tab: string | number) {
  if (tab === "project-pool") void loadApplicationWorkspace();
}

watch(() => [props.ownerUserId, props.ownerMemberId], () => {
  detailCache.clear();
  selected.value = null;
  directoryTeams.value = [];
  directoryLoaded.value = false;
  directoryPage.value = 1;
  clearDetail();
  void loadCatalog();
});

onMounted(() => { void loadCatalog(); });
</script>

<template>
  <section class="page-stack">
    <header class="page-heading team-page-heading">
      <div>
        <p class="eyebrow">Team operations</p>
        <h1>{{ adminMode ? "企业团队" : "我的团队" }}</h1>
        <p>{{ adminMode ? "从团队结构、运行配额到成员权益统一管理企业网关团队。" : "只查看负责人所属团队的额度健康度、成员与权益。" }}</p>
      </div>
      <div class="team-heading-actions">
        <div v-if="adminMode" class="team-view-switcher" :data-view="viewMode" role="group" aria-label="团队视图">
          <span class="team-view-indicator" aria-hidden="true" />
          <button type="button" :aria-pressed="viewMode === 'cards'" @click="switchView('cards')">活跃团队总览</button>
          <button type="button" :aria-pressed="viewMode === 'table'" @click="switchView('table')">团队目录</button>
        </div>
        <el-button v-if="adminMode" type="primary" @click="openCreateTeam">新增团队</el-button>
      </div>
    </header>

    <Transition name="team-view" mode="out-in">
      <section v-if="adminMode && viewMode === 'cards'" key="cards" v-loading="catalogLoading" class="team-catalog">
        <div class="section-title">
          <div><p class="eyebrow">Active teams</p><h2>活跃团队总览</h2><p class="muted">选择一个团队进入下方的额度、用量和成员工作台。</p></div>
          <el-tag type="success" effect="plain">{{ activeTeams.length }} 个运行中</el-tag>
        </div>
        <div v-if="activeTeams.length" class="team-card-grid">
          <article v-for="team in activeTeams" :key="team.teamId" class="team-summary-card" :class="{ selected: team.teamId === selected?.teamId }" @click="select(team)">
            <div class="team-card-toolbar">
              <span class="team-card-kicker">TEAM {{ team.teamId }}</span>
              <el-dropdown trigger="click" @command="action => runTeamAction(action, team)">
                <el-button class="team-card-menu" text circle aria-label="团队操作" @click.stop>···</el-button>
                <template #dropdown><el-dropdown-menu><el-dropdown-item command="edit">编辑团队</el-dropdown-item><el-dropdown-item command="dissolve" divided class="danger-menu-item">解散团队</el-dropdown-item></el-dropdown-menu></template>
              </el-dropdown>
            </div>
            <strong>{{ team.name }}</strong><span class="team-card-owner">负责人 {{ team.ownerName || "待指定" }}</span>
            <div><span>{{ team.memberCount }} 成员</span><span>{{ team.keyCount }} Key</span></div>
            <small>RPM {{ team.teamRpm }} · 并发 {{ team.teamConcurrency }}</small>
          </article>
        </div>
        <div v-else class="team-catalog-empty">当前没有启用团队。创建并指定负责人后即可开始分配模型权益。</div>
      </section>

      <section v-else-if="adminMode" key="directory" v-loading="directoryLoading" class="surface team-table-surface">
        <div class="section-title">
          <div><p class="eyebrow">Team directory</p><h2>团队目录</h2><p class="muted">检索全量团队；已解散团队仅保留历史记录，不能重新投入网关运行。</p></div>
          <el-tag effect="plain">{{ directoryTotal }} 个团队</el-tag>
        </div>
        <div class="team-directory-filters">
          <el-input v-model="directoryKeyword" clearable placeholder="搜索团队、ID、负责人或邮箱" @clear="resetDirectory" @keyup.enter="resetDirectory" />
          <el-select v-model="directoryStatus" @change="resetDirectory"><el-option label="全部状态" value="ALL" /><el-option label="运行中" value="RUNNING" /><el-option label="草稿" value="DRAFT" /><el-option label="待开通" value="READY_FOR_REQUEST" /><el-option label="已暂停" value="SUSPENDED" /><el-option label="已解散" value="DISSOLVED" /></el-select>
          <el-select v-model="directoryOwner" filterable @change="resetDirectory"><el-option label="全部负责人" value="ALL" /><el-option label="未指定负责人" value="UNASSIGNED" /><el-option v-for="user in directoryOwnerOptions" :key="user.userId" :label="`${user.name} · ${user.email}`" :value="user.userId" /></el-select>
          <el-button type="primary" plain @click="resetDirectory">搜索</el-button>
        </div>
        <el-table :data="directoryTeams" size="small" row-key="teamId" @row-click="select">
          <el-table-column prop="name" label="团队" min-width="180"><template #default="scope"><strong>{{ scope.row.name }}</strong><small class="table-subline">ID {{ scope.row.teamId }}</small></template></el-table-column>
          <el-table-column label="负责人" min-width="170"><template #default="scope">{{ scope.row.ownerName || "未指定" }}<small v-if="scope.row.ownerEmail" class="table-subline">{{ scope.row.ownerEmail }}</small></template></el-table-column>
          <el-table-column label="规模" width="130"><template #default="scope">{{ scope.row.memberCount }} 成员 · {{ scope.row.keyCount }} Key</template></el-table-column>
          <el-table-column label="运行配额" min-width="190"><template #default="scope">{{ scope.row.teamRpm }} RPM · {{ scope.row.teamConcurrency }} 并发</template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'" effect="plain">{{ teamStatusLabel(scope.row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="管理" width="90" fixed="right"><template #default="scope"><el-dropdown v-if="scope.row.enabled" trigger="click" @command="action => runTeamAction(action, scope.row)"><el-button class="team-table-menu" text circle aria-label="团队操作" @click.stop>···</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="edit">编辑团队</el-dropdown-item><el-dropdown-item command="dissolve" divided class="danger-menu-item">解散团队</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
        </el-table>
        <div v-if="!directoryLoading && !directoryTeams.length" class="team-directory-empty">没有符合当前条件的团队。</div>
        <el-pagination v-if="directoryTotal > directorySize" class="team-directory-pagination" layout="total, prev, pager, next" :current-page="directoryPage" :page-size="directorySize" :total="directoryTotal" @current-change="changeDirectoryPage" />
      </section>
    </Transition>

    <template v-if="selected">
      <el-tabs v-model="activeTab" class="team-tabs" @tab-change="handleTabChange">
        <el-tab-pane label="概览" name="overview">
          <section class="surface team-overview-surface" :class="{ refreshing: detailLoading }">
            <header class="team-overview-heading">
              <div><div class="team-name-line"><h2>{{ selected.name }}</h2><el-tag :type="selectedIsOperational ? 'success' : 'info'" effect="plain">{{ teamStatusLabel(selected.status) }}</el-tag><el-tag v-if="detailLoading" type="info" effect="plain">{{ detailVisible ? "正在刷新" : "正在加载" }}</el-tag></div><p class="muted">负责人 {{ selected.ownerName || "未指定" }} · {{ selected.memberCount }} 名活动成员 · {{ selected.keyCount }} 把活动 Key</p></div>
            </header>
            <p v-if="!selectedIsOperational" class="dissolved-team-notice">此团队已解散，仅展示历史额度与用量快照；成员、Key 与管理操作均已停用。</p>
            <div v-if="!detailVisible" class="team-detail-skeleton"><el-skeleton animated :rows="8" /><p>正在加载 {{ selected.name }} 的额度、用量和成员概览…</p></div>
            <template v-else><QuotaVisualizationPanel :items="quotaItems" :unlimited-count="unlimitedCount" :trends="dashboard?.lastSevenDays ?? []" :member-ranking="dashboard?.memberRanking ?? []" title="团队当前周期额度" /><p class="overview-note">额度构成、模型消耗、近 7 日趋势与成员排名均属于当前团队，不混入其他团队数据。</p></template>
          </section>
        </el-tab-pane>
        <el-tab-pane label="开发额度池" name="entitlements"><section class="surface"><div class="section-title"><div><h2>开发额度池与模型权益</h2><p class="muted">开发额度池用于开发成员及其凭证；成员分配不会重复计入团队使用。</p></div><el-button v-if="adminMode && selectedIsOperational" text type="primary" @click="openEntitlement()">发放模型权益</el-button></div><el-table :data="entitlements" size="small"><el-table-column prop="modelName" label="模型" min-width="180" /><el-table-column prop="quotaMode" label="周期" width="90"><template #default="scope">{{ scope.row.quotaMode === "WEEKLY" ? "每周" : scope.row.quotaMode === "DAILY" ? "每日" : "不限" }}</template></el-table-column><el-table-column label="额度上限" min-width="120"><template #default="scope">{{ formatTokenCount(scope.row.quotaLimit) }}</template></el-table-column><el-table-column label="当前周期" min-width="260"><template #default="scope">{{ formatTokenCount(scope.row.consumedTokens) }} 已用 · {{ formatTokenCount(scope.row.frozenTokens) }} 冻结 · {{ formatTokenCount(scope.row.remainingTokens) }} 剩余</template></el-table-column><el-table-column prop="status" label="状态" width="100" /><el-table-column v-if="adminMode && selectedIsOperational" label="操作" width="140"><template #default="scope"><el-button text @click="openEntitlement(undefined, scope.row)">调整</el-button><el-button v-if="scope.row.status === 'ACTIVE'" text type="danger" @click="revokeTeam(scope.row)">收回</el-button></template></el-table-column></el-table></section></el-tab-pane>
        <el-tab-pane v-if="adminMode || canManageProjectPool" :label="adminMode ? '项目额度池配置' : '项目额度池'" name="project-pool" @click="loadApplicationWorkspace()">
          <section v-if="adminMode" class="surface application-pool-admin">
            <div class="section-title"><div><p class="eyebrow">Application pool provisioning</p><h2>团队项目额度池配置</h2><p class="muted">平台管理员只负责向团队授予应用模型与初始额度；项目、服务账号和应用 Key 仅由团队负责人管理。</p></div><el-button type="primary" :disabled="!selectedIsOperational" @click="openApplicationPool">配置项目额度池</el-button></div>
            <el-alert type="info" :closable="false" title="项目额度与开发额度隔离" description="此处配置的 Token 仅进入团队项目额度池，不会增加开发成员额度，也不会展示团队内项目和应用凭证。" />
          </section>
          <section v-else class="project-pool-workspace" v-loading="applicationLoading">
            <section class="surface project-pool-summary">
              <div class="section-title"><div><p class="eyebrow">Application quota pool</p><h2>团队项目额度池</h2><p class="muted">项目与服务账号只能消费此额度池中已划拨的额度，不会占用开发额度池。</p></div><el-button type="primary" :disabled="!selectedIsOperational || !hasActiveOwner" @click="openProjectDialog">新建项目</el-button></div>
              <div class="detail-metrics"><span>可用 Token<strong>{{ formatTokenCount(applicationQuota?.balance.availableTokens ?? 0) }}</strong></span><span>冻结 Token<strong>{{ formatTokenCount(applicationQuota?.balance.frozenTokens ?? 0) }}</strong></span><span>已消费 Token<strong>{{ formatTokenCount(applicationQuota?.balance.consumedTokens ?? 0) }}</strong></span><span>可用模型<strong>{{ applicationModels.length }}</strong></span></div>
              <div class="application-model-tags"><el-tag v-for="model in applicationModels" :key="model" effect="plain">{{ model }}</el-tag><span v-if="!applicationModels.length" class="muted">平台管理员尚未向此团队授予项目模型与额度。</span></div>
            </section>
            <section class="team-master project-master">
              <aside class="surface team-list project-list"><div class="section-title"><div><h3>项目目录</h3><p class="muted">{{ projects.length }} 个项目</p></div></div><div v-if="projects.length" class="compact-list"><button v-for="project in projects" :key="project.projectId" type="button" class="list-row" :class="{ selected: selectedProject?.projectId === project.projectId }" @click="selectProject(project)"><strong>{{ project.name }}</strong><small>{{ project.projectCode }} · {{ project.enabled ? '运行中' : '已停用' }}</small></button></div><p v-else class="muted">尚未创建项目。项目用于隔离业务服务的额度和应用凭证。</p></aside>
              <section class="surface team-detail project-detail" v-loading="projectLoading">
                <template v-if="selectedProject"><div class="section-title"><div><p class="eyebrow">Project application quota</p><h2>{{ selectedProject.name }}</h2><p class="muted">{{ selectedProject.projectCode }} · {{ selectedProject.enabled ? '运行中' : '已停用' }}</p></div><div class="team-context-actions"><el-button :disabled="!selectedProject.enabled || !applicationModels.length" type="primary" plain @click="openProjectAllocation">划拨项目额度</el-button><el-button v-if="selectedProject.enabled" type="danger" plain @click="disableProject(selectedProject)">停用项目</el-button></div></div>
                  <div class="detail-metrics"><span>可用 Token<strong>{{ formatTokenCount(projectQuota?.balance.availableTokens ?? 0) }}</strong></span><span>冻结 Token<strong>{{ formatTokenCount(projectQuota?.balance.frozenTokens ?? 0) }}</strong></span><span>已消费 Token<strong>{{ formatTokenCount(projectQuota?.balance.consumedTokens ?? 0) }}</strong></span><span>服务账号<strong>{{ serviceAccounts.length }}</strong></span></div>
                  <section class="detail-block"><h3>项目模型授权</h3><div class="application-model-tags"><el-tag v-for="item in projectQuota?.modelEntitlements ?? []" :key="item.grantId" type="success" effect="plain">{{ item.modelName }} · {{ formatTokenCount(item.quotaLimit) }}</el-tag><span v-if="!projectQuota?.modelEntitlements.length" class="muted">尚未划拨模型额度。</span></div></section>
                  <section class="detail-block"><div class="section-title"><div><h3>服务账号与应用 Key</h3><p class="muted">应用 Key 只显示一次；停用服务账号会立即停用对应 Key。</p></div><el-button type="primary" plain :disabled="!selectedProject.enabled" @click="openServiceAccountDialog">新建服务账号</el-button></div><el-table :data="serviceAccounts" size="small"><el-table-column prop="name" label="服务账号" min-width="160" /><el-table-column label="应用 Key" min-width="180"><template #default="scope"><code v-if="scope.row.keyPrefix">{{ scope.row.keyPrefix }}</code><span v-else class="muted">尚未生成</span></template></el-table-column><el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '活动' : '已停用' }}</el-tag></template></el-table-column><el-table-column label="操作" min-width="230" fixed="right"><template #default="scope"><el-button v-if="scope.row.enabled && !scope.row.keyEnabled" text type="primary" @click="createApplicationKey(scope.row)">生成 Key</el-button><el-button v-else-if="scope.row.enabled" text type="primary" @click="createApplicationKey(scope.row, true)">轮换 Key</el-button><el-button v-if="scope.row.enabled" text type="danger" @click="updateServiceAccount(scope.row, false)">停用</el-button><el-button v-else text type="primary" @click="updateServiceAccount(scope.row, true)">启用</el-button></template></el-table-column></el-table><p v-if="!serviceAccounts.length" class="muted">尚未创建服务账号。</p></section>
                </template><div v-else class="empty-state"><h2>请选择项目</h2><p>从左侧项目目录选择一个项目，查看其额度、模型权限和应用凭证。</p></div>
              </section>
            </section>
          </section>
        </el-tab-pane>
        <el-tab-pane label="成员" name="members"><section class="surface"><div class="section-title"><div><h2>团队成员</h2><p class="muted">成员资料由平台用户目录维护；这里管理成员关系、状态和个人模型权益。</p></div><div class="member-heading-actions"><el-button v-if="adminMode && selectedIsOperational" @click="openOwnerDialog">变更负责人</el-button><el-button type="primary" :disabled="!canManageMembers" @click="openMemberDialog">添加成员</el-button></div></div><p v-if="!hasActiveOwner && selectedIsOperational" class="member-management-warning">请先为团队指定启用的负责人，才能添加或移出成员。</p><el-table :data="members" size="small" @row-click="selectMember"><el-table-column prop="name" label="成员" min-width="160" /><el-table-column prop="email" label="邮箱" min-width="220" /><el-table-column prop="role" label="角色" width="100"><template #default="scope">{{ scope.row.role === "OWNER" ? "负责人" : "开发成员" }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? "活动" : "已停用" }}</el-tag></template></el-table-column><el-table-column label="操作" width="210"><template #default="scope"><el-button v-if="scope.row.enabled && !adminMode && selectedIsOperational" text type="primary" @click.stop="openEntitlement(scope.row)">权益</el-button><el-button v-if="scope.row.enabled && scope.row.role !== 'OWNER' && selectedIsOperational" text type="danger" :disabled="!canManageMembers" @click.stop="deactivateMember(scope.row)">移出</el-button></template></el-table-column></el-table></section><section v-if="selectedMember" class="surface member-entitlement-detail"><div class="section-title"><div><h2>{{ selectedMember.name }} 的模型权益</h2><p class="muted">权益变更不会生成 Key；成员确认权益后可在自己的页面生成或轮换 Key。</p></div><el-button v-if="!adminMode && selectedIsOperational" text type="primary" @click="openEntitlement(selectedMember)">新增权益</el-button></div><el-table :data="memberEntitlements" size="small"><el-table-column prop="modelName" label="模型" /><el-table-column prop="quotaMode" label="周期" width="90" /><el-table-column label="上限"><template #default="scope">{{ formatTokenCount(scope.row.quotaLimit) }}</template></el-table-column><el-table-column label="已用 / 剩余" min-width="200"><template #default="scope">{{ formatTokenCount(scope.row.consumedTokens) }} / {{ formatTokenCount(scope.row.remainingTokens) }}</template></el-table-column><el-table-column v-if="!adminMode && selectedIsOperational" label="操作" width="140"><template #default="scope"><el-button text @click="openEntitlement(selectedMember, scope.row)">调整</el-button><el-button v-if="scope.row.status === 'ACTIVE'" text type="danger" @click="revokeMember(scope.row)">收回</el-button></template></el-table-column></el-table></section></el-tab-pane>
      </el-tabs>
    </template>
    <section v-else-if="!catalogLoading" class="surface empty-state"><h2>{{ adminMode ? "尚无可查看的团队" : "未找到负责人所属的启用团队" }}</h2><p>{{ adminMode ? "创建团队并指定负责人后即可配置成员、权益与网关配额。" : "已解散团队不会出现在负责人视图中。" }}</p></section>

    <el-dialog v-model="dialog" :title="dialog === 'team' ? '新增团队' : dialog === 'edit' ? '编辑团队' : dialog === 'owner' ? '变更团队负责人' : dialog === 'member' ? '添加团队成员' : dialog === 'application-pool' ? '配置团队项目额度池' : dialog === 'project' ? '新建项目' : dialog === 'project-allocation' ? '划拨项目额度' : dialog === 'service-account' ? '新建服务账号' : '模型权益'" width="560px">
      <el-form v-if="dialog === 'team'" label-position="top"><el-form-item label="团队名称"><el-input v-model="teamForm.name" /></el-form-item><el-form-item label="初始负责人"><el-select v-model="teamForm.ownerUserId" clearable><el-option v-for="user in users.filter(user => user.enabled && user.teamId === null)" :key="user.userId" :label="`${user.name} · ${user.email}`" :value="user.userId" /></el-select></el-form-item><el-form-item label="团队 RPM"><el-input-number v-model="teamForm.teamRpm" :min="1" /></el-form-item></el-form>
      <el-form v-else-if="dialog === 'edit'" label-position="top"><el-form-item label="团队名称"><el-input v-model="teamForm.name" /></el-form-item><el-form-item label="团队 RPM"><el-input-number v-model="teamForm.teamRpm" :min="1" /></el-form-item><el-form-item label="团队并发"><el-input-number v-model="teamForm.teamConcurrency" :min="1" /></el-form-item><el-form-item label="模型并发"><el-input-number v-model="teamForm.modelConcurrency" :min="1" /></el-form-item></el-form>
      <el-form v-else-if="dialog === 'owner'" label-position="top"><el-form-item label="新的团队负责人"><el-select v-model="ownerUserId" filterable placeholder="选择启用且可分配的用户"><el-option v-for="user in ownerCandidates" :key="user.userId" :label="`${user.name} · ${user.email}`" :value="user.userId" /></el-select></el-form-item><p class="muted">当前负责人会保留在团队内并降为开发成员；目标用户不能属于其他活动团队。</p></el-form>
      <el-form v-else-if="dialog === 'member'" label-position="top"><el-form-item label="选择用户"><el-select v-model="candidateId" filterable placeholder="选择未分配用户"><el-option v-for="candidate in candidates" :key="candidate.userId" :label="`${candidate.name} · ${candidate.email}`" :value="candidate.userId"><span>{{ candidate.name }} · {{ candidate.email }}</span><small class="candidate-status">{{ candidate.rejoining ? `重新加入（此前在 ${candidate.previousTeamName || '其他团队'}）` : "未分配" }}</small></el-option></el-select></el-form-item><p v-if="!candidates.length" class="muted">暂无可加入的启用用户。请先在“用户管理”中新增用户，或移出其他团队成员。</p></el-form>
      <el-form v-else-if="dialog === 'application-pool'" label-position="top"><el-form-item label="允许项目调用的模型"><el-select v-model="applicationPoolForm.modelNames" multiple filterable><el-option v-for="model in allModels" :key="model" :label="model" :value="model" /></el-select></el-form-item><el-form-item label="授予 Token"><QuotaAmountInput v-model="applicationPoolForm.tokenAllocation" scope="TEAM" @validity="valid => quotaInputValid = valid" /></el-form-item><el-form-item label="说明"><el-input v-model="applicationPoolForm.reason" /></el-form-item><p class="muted">Token 进入团队项目额度池，团队负责人会在此范围内为项目划拨额度。</p></el-form>
      <el-form v-else-if="dialog === 'project'" label-position="top"><el-form-item label="项目名称"><el-input v-model="projectForm.name" maxlength="80" show-word-limit /></el-form-item><el-form-item label="项目编码"><el-input v-model="projectForm.projectCode" maxlength="64" show-word-limit placeholder="例如 rag-platform" /></el-form-item><p class="muted">项目编码创建后用于识别业务边界，请保持稳定且不包含敏感信息。</p></el-form>
      <el-form v-else-if="dialog === 'project-allocation'" label-position="top"><el-form-item label="项目模型"><el-select v-model="projectAllocationForm.modelNames" multiple filterable><el-option v-for="model in applicationModels" :key="model" :label="model" :value="model" /></el-select></el-form-item><el-form-item label="划拨 Token"><QuotaAmountInput v-model="projectAllocationForm.tokenAllocation" scope="TEAM" @validity="valid => quotaInputValid = valid" /></el-form-item><el-form-item label="说明"><el-input v-model="projectAllocationForm.reason" /></el-form-item><p class="muted">划拨后 Token 从团队项目额度池转入当前项目，开发额度池不受影响。</p></el-form>
      <el-form v-else-if="dialog === 'service-account'" label-position="top"><el-form-item label="服务账号名称"><el-input v-model="serviceAccountForm.name" maxlength="80" show-word-limit placeholder="例如 production-agent" /></el-form-item><p class="muted">服务账号用于 Agent、RAG 或业务服务调用；创建后需单独生成应用 Key。</p></el-form>
      <el-form v-else label-position="top"><el-form-item label="模型"><el-select v-model="entitlementForm.modelName" filterable><el-option v-for="model in allModels" :key="model" :label="model" :value="model" /></el-select></el-form-item><el-form-item label="额度类型"><el-select v-model="entitlementForm.quotaMode" @change="quotaModeChanged"><el-option label="每日" value="DAILY" /><el-option label="每周" value="WEEKLY" /><el-option label="不限" value="UNLIMITED" /></el-select></el-form-item><el-form-item label="Token 上限"><QuotaAmountInput v-model="entitlementForm.quotaLimit" :scope="entitlementScope" :disabled="entitlementForm.quotaMode === 'UNLIMITED'" @validity="valid => quotaInputValid = valid" /></el-form-item><el-form-item label="说明"><el-input v-model="entitlementForm.reason" /></el-form-item></el-form>
      <template #footer><el-button @click="dialog = null">取消</el-button><el-button type="primary" :disabled="(dialog === 'member' && !candidateId) || ((dialog === 'application-pool' || dialog === 'project-allocation') && !quotaInputValid)" @click="dialog === 'team' ? saveTeam() : dialog === 'edit' ? saveEdit() : dialog === 'owner' ? saveOwner() : dialog === 'member' ? saveMember() : dialog === 'application-pool' ? saveApplicationPool() : dialog === 'project' ? saveProject() : dialog === 'project-allocation' ? saveProjectAllocation() : dialog === 'service-account' ? saveServiceAccount() : saveEntitlement()">保存</el-button></template>
    </el-dialog>
  </section>
</template>
