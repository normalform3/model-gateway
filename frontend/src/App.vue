<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import AdminDashboardPage from "./pages/AdminDashboardPage.vue";
import BillingPage from "./pages/BillingPage.vue";
import ProviderPage from "./pages/ProviderPage.vue";
import TeamsPage from "./pages/TeamsPage.vue";
import ApiKeysPage from "./pages/ApiKeysPage.vue";
import UsersPage from "./pages/UsersPage.vue";
import EntitlementRequestsPage from "./pages/EntitlementRequestsPage.vue";
import DeveloperDashboardPage from "./pages/DeveloperDashboardPage.vue";
import LoginPage from "./pages/LoginPage.vue";
import NoTeamPage from "./pages/NoTeamPage.vue";
import { api, type ConsoleIdentity } from "./api";

type Page = "dashboard" | "billing" | "users" | "providers" | "teams" | "requests" | "keys";
interface NavItem { key: Page; label: string; caption: string; }
const identity = ref<ConsoleIdentity | null>(null); const restoring = ref(true); const page = ref<Page>("dashboard");
const menus = computed<Record<string, NavItem[]>>(() => ({
  PLATFORM_ADMIN: [{ key: "dashboard", label: "仪表盘", caption: "全局状态与保护" }, { key: "billing", label: "账单分析", caption: "全平台成本与用量归因" }, { key: "teams", label: "企业团队", caption: "团队与权益管理" }, { key: "requests", label: "授权申请", caption: "集中审批团队申请" }, { key: "providers", label: "模型供应商", caption: "凭据、模型与价格" }, { key: "users", label: "用户管理", caption: "用户、团队归属与角色" }],
  TEAM_ADMIN: [{ key: "teams", label: "团队概览", caption: "仅所属团队" }, { key: "keys", label: "我的虚拟 API Key", caption: "负责人自己的调用凭据" }],
  DEVELOPER: [{ key: "dashboard", label: "开发者工作台", caption: "个人模型权益与用量" }, { key: "keys", label: "我的 Key", caption: "仅当前开发成员" }],
  UNASSIGNED: [{ key: "dashboard", label: "账户状态", caption: "团队与额度状态" }]
}));
function accept(next: ConsoleIdentity) { identity.value = next; page.value = menus.value[next.role][0].key; }
async function restore() { try { const response = await api.refresh(); if (response) accept(response.identity); } finally { restoring.value = false; } }
async function logout() { try { await api.logout(); } catch { /* local state is cleared even when the network request fails */ } identity.value = null; }
async function changePassword() { const currentPassword = window.prompt("请输入当前密码"); const newPassword = window.prompt("请输入至少 12 位的新密码"); if (!currentPassword || !newPassword) return; try { await api.changePassword(currentPassword, newPassword); identity.value = null; ElMessage.success("密码已更新，请重新登录"); } catch (error) { ElMessage.error(error instanceof Error ? error.message : "密码更新失败"); } }
onMounted(restore);
</script>
<template>
  <main v-if="restoring" class="login-shell"><section class="login-card"><p>正在恢复安全会话…</p></section></main>
  <LoginPage v-else-if="!identity" @authenticated="accept" />
  <main v-else class="console-shell"><aside class="rail"><div class="brand"><span class="brand-mark">MG</span><div><strong>ModelGate</strong><small>CONTROL PLANE</small></div></div><div class="identity-switcher"><span>当前登录用户</span><strong>{{ identity.name }}</strong><small>{{ identity.role === 'PLATFORM_ADMIN' ? '平台管理员' : identity.role === 'TEAM_ADMIN' ? '团队管理员' : identity.role === 'DEVELOPER' ? '开发者' : '未分配团队用户' }}</small></div><nav class="rail-nav" aria-label="工作台导航"><button v-for="item in menus[identity.role]" :key="item.key" class="nav-item" :class="{ active: page === item.key }" @click="page = item.key"><strong>{{ item.label }}</strong><small>{{ item.caption }}</small></button></nav><div class="rail-footer"><el-button text @click="changePassword">修改密码</el-button><el-button text type="danger" @click="logout">退出登录</el-button><small v-if="identity.passwordChangeRequired">首次登录，请先修改密码。</small></div></aside><section class="workspace"><AdminDashboardPage v-if="page === 'dashboard' && identity.role === 'PLATFORM_ADMIN'" @open-billing="page = 'billing'" /><NoTeamPage v-else-if="page === 'dashboard' && identity.role === 'UNASSIGNED'" /><BillingPage v-else-if="page === 'billing'" /><DeveloperDashboardPage v-else-if="page === 'dashboard'" :member-id="identity.memberId" /><UsersPage v-else-if="page === 'users'" /><ProviderPage v-else-if="page === 'providers'" /><TeamsPage v-else-if="page === 'teams'" :owner-user-id="identity.role === 'TEAM_ADMIN' ? identity.userId : null" :owner-member-id="identity.memberId" :admin-mode="identity.role === 'PLATFORM_ADMIN'" /><EntitlementRequestsPage v-else-if="page === 'requests'" /><ApiKeysPage v-else :default-team-id="identity.teamId" :default-member-id="identity.memberId" :owner-member-id="identity.role === 'TEAM_ADMIN' ? identity.memberId : null" /></section></main>
</template>
