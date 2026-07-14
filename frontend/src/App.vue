<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import AdminDashboardPage from "./pages/AdminDashboardPage.vue";
import ProviderPage from "./pages/ProviderPage.vue";
import TeamsPage from "./pages/TeamsPage.vue";
import ApiKeysPage from "./pages/ApiKeysPage.vue";
import UsersPage from "./pages/UsersPage.vue";
import EntitlementRequestsPage from "./pages/EntitlementRequestsPage.vue";
import DeveloperDashboardPage from "./pages/DeveloperDashboardPage.vue";
import { api, type PlatformUser } from "./api";

type RoleView = "platform-admin" | "team-admin" | "developer";
type Page = "dashboard" | "users" | "providers" | "teams" | "requests" | "keys";
interface NavItem { key: Page; label: string; caption: string; }

const ROLE_STORAGE_KEY = "modelgate-role-view";
const USER_STORAGE_KEY = "modelgate-view-user";
const testToolUrl = import.meta.env.VITE_TEST_TOOL_URL || "http://127.0.0.1:19090";
const roleView = ref<RoleView>((window.localStorage.getItem(ROLE_STORAGE_KEY) as RoleView) || "platform-admin");
const users = ref<PlatformUser[]>([]);
const selectedUserId = ref<number | null>(Number(window.localStorage.getItem(USER_STORAGE_KEY)) || null);
const loading = ref(false);
const loadError = ref("");
const page = ref<Page>("dashboard");

const menus: Record<RoleView, NavItem[]> = {
  "platform-admin": [
    { key: "dashboard", label: "仪表盘", caption: "全局状态与保护" },
    { key: "teams", label: "企业团队", caption: "团队与权益管理" },
    { key: "requests", label: "授权申请", caption: "集中审批团队申请" },
    { key: "providers", label: "模型供应商", caption: "凭据、模型与价格" },
    { key: "users", label: "用户管理", caption: "用户、团队归属与角色" }
  ],
  "team-admin": [
    { key: "teams", label: "团队概览", caption: "仅当前负责人所属团队" },
    { key: "keys", label: "我的虚拟 API Key", caption: "负责人自己的调用凭据" }
  ],
  developer: [
    { key: "dashboard", label: "开发者工作台", caption: "个人模型权益与用量" },
    { key: "keys", label: "我的 Key", caption: "仅当前开发成员" }
  ]
};

const currentUser = computed(() => users.value.find(user => user.userId === selectedUserId.value) ?? null);
const needsUser = computed(() => roleView.value !== "platform-admin");
const userRole = computed(() => roleView.value === "team-admin" ? "OWNER" : "DEVELOPER");

async function loadUsers() {
  if (!needsUser.value) { users.value = []; selectedUserId.value = null; return; }
  loading.value = true; loadError.value = "";
  try {
    const response = await api.users({ role: userRole.value, enabledOnly: true });
    users.value = response.items;
    const next = users.value.find(user => user.userId === selectedUserId.value) ?? users.value[0] ?? null;
    selectedUserId.value = next?.userId ?? null;
    if (next) window.localStorage.setItem(USER_STORAGE_KEY, String(next.userId));
  } catch (error) { loadError.value = error instanceof Error ? error.message : "用户列表加载失败"; }
  finally { loading.value = false; }
}

function changeRole(event: Event) {
  roleView.value = (event.target as HTMLSelectElement).value as RoleView;
  page.value = menus[roleView.value][0].key;
}
function changeUser(event: Event) {
  selectedUserId.value = Number((event.target as HTMLSelectElement).value) || null;
  if (selectedUserId.value) window.localStorage.setItem(USER_STORAGE_KEY, String(selectedUserId.value));
}
function openTestTool() {
  window.open(testToolUrl, "modelgate-test-runner", "noopener,noreferrer");
}
watch(roleView, () => { window.localStorage.setItem(ROLE_STORAGE_KEY, roleView.value); void loadUsers(); });
onMounted(loadUsers);
</script>

<template>
  <main class="console-shell">
    <aside class="rail">
      <div class="brand"><span class="brand-mark">MG</span><div><strong>ModelGate</strong><small>CONTROL PLANE</small></div></div>
      <label class="identity-switcher"><span>开发期角色视角</span><select :value="roleView" @change="changeRole"><option value="platform-admin">平台管理员</option><option value="team-admin">团队负责人</option><option value="developer">开发成员</option></select></label>
      <label v-if="needsUser" class="identity-switcher"><span>{{ roleView === 'team-admin' ? '负责人用户' : '开发成员用户' }}</span><select :value="selectedUserId ?? ''" :disabled="loading || !users.length" @change="changeUser"><option v-for="user in users" :key="user.userId" :value="user.userId">{{ user.name }} · {{ user.teamName ?? '未分配团队' }}</option></select></label>
      <nav class="rail-nav" aria-label="工作台导航"><button v-for="item in menus[roleView]" :key="item.key" class="nav-item" :class="{ active: page === item.key }" @click="page = item.key"><strong>{{ item.label }}</strong><small>{{ item.caption }}</small></button></nav>
      <button class="nav-item test-tool-link" @click="openTestTool"><strong>开发测试工具 ↗</strong><small>独立 Runner · Mock 调用压测</small></button>
      <div class="rail-footer"><span>当前操作上下文</span><strong>{{ roleView === 'platform-admin' ? '平台管理员视角' : currentUser?.name ?? '请选择用户' }}</strong><small>{{ currentUser?.teamName ?? '平台全局视角' }}</small><small>选择仅控制页面与接口筛选，不是登录或 RBAC。</small><small v-if="loadError">{{ loadError }}</small></div>
    </aside>
    <section class="workspace">
      <AdminDashboardPage v-if="page === 'dashboard' && roleView === 'platform-admin'" />
      <DeveloperDashboardPage v-else-if="page === 'dashboard'" :member-id="currentUser?.memberId" />
      <UsersPage v-else-if="page === 'users'" />
      <ProviderPage v-else-if="page === 'providers'" />
      <TeamsPage v-else-if="page === 'teams'" :owner-user-id="roleView === 'team-admin' ? selectedUserId : null" :owner-member-id="roleView === 'team-admin' ? currentUser?.memberId : null" :admin-mode="roleView === 'platform-admin'" />
      <EntitlementRequestsPage v-else-if="page === 'requests'" />
      <ApiKeysPage v-else :default-team-id="currentUser?.teamId" :default-member-id="currentUser?.memberId" :owner-member-id="roleView === 'team-admin' ? currentUser?.memberId : null" />
    </section>
  </main>
</template>
