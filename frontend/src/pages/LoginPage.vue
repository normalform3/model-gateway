<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { ApiError, api, type ConsoleIdentity } from "../api";
const emit = defineEmits<{ authenticated: [identity: ConsoleIdentity] }>();
const form = reactive({ email: "", password: "" }); const loading = ref(false);
function failureMessage(error: unknown) {
  if (!(error instanceof ApiError)) return "网络或登录服务暂时不可用，请稍后重试。";
  if (error.code === "AUTHENTICATION_FAILED") {
    return error.message.includes("Too many failed login attempts")
      ? "登录失败次数过多，请 15 分钟后再试。"
      : "账号不存在、已停用或密码错误，请检查后重试。";
  }
  if (error.code === "ACCESS_DENIED") return "该账号当前没有可用的控制台权限，请联系平台管理员。";
  if (error.code === "BAD_MODEL_REQUEST") return "请填写账号和密码。";
  if (error.code === "INTERNAL_ERROR") return "登录服务暂时异常，请稍后重试。";
  return error.message || "登录失败，请稍后重试。";
}
async function submit() { loading.value = true; try { const response = await api.login(form.email, form.password); emit("authenticated", response.identity); } catch (error) { await ElMessageBox.alert(failureMessage(error), "无法登录", { type: "error", confirmButtonText: "知道了" }); } finally { loading.value = false; } }
</script>
<template><main class="login-shell"><section class="login-card"><div class="brand"><span class="brand-mark">MG</span><div><strong>ModelGate</strong><small>CONTROL PLANE</small></div></div><div class="login-heading"><p class="eyebrow">Secure access</p><h1>登录管理控制台</h1><p>使用平台分配的账号进入与你权限匹配的工作台。</p></div><el-form label-position="top" @submit.prevent="submit"><el-form-item label="账号 / 邮箱"><el-input v-model="form.email" autocomplete="username" placeholder="admin 或 name@example.com" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" @keyup.enter="submit" /></el-form-item><el-button native-type="submit" type="primary" :loading="loading" :disabled="!form.email || !form.password" class="login-submit">登录</el-button></el-form></section></main></template>
