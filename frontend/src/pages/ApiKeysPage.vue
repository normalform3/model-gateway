<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, type MemberKeyStatus, type QuotaBalance } from "../api";

const props = defineProps<{ defaultMemberId?: number | null; defaultTeamId?: number | null; ownerMemberId?: number | null }>();
const status = ref<MemberKeyStatus | null>(null); const quota = ref<QuotaBalance | null>(null); const loading = ref(false);
async function load() { if (!props.defaultMemberId) return; loading.value = true; try { [status.value, quota.value] = await Promise.all([api.memberKeyStatus(props.defaultMemberId), api.memberQuota(props.defaultMemberId)]); } catch (error) { ElMessage.error(error instanceof Error ? error.message : "Key 状态加载失败"); } finally { loading.value = false; } }
async function generate(rotate = false) { if (!props.defaultMemberId) return; try { if (rotate) await ElMessageBox.confirm("旧 Key 会立即失效，新 Key 只展示一次。", "确认轮换", { type: "warning" }); const result = rotate ? await api.rotateMemberKey(props.defaultMemberId) : await api.generateMemberKey(props.defaultMemberId); await ElMessageBox.alert(`<p>请立即安全保存，关闭后无法再次查看。</p><code>${result.apiKey}</code>`, "你的虚拟 API Key", { dangerouslyUseHTMLString: true, confirmButtonText: "我已保存" }); await load(); } catch (error) { if (error !== "cancel" && error !== "close") ElMessage.error(error instanceof Error ? error.message : "Key 生成失败"); } }
watch(() => props.defaultMemberId, () => { void load(); }); onMounted(load);
</script>
<template>
  <section class="page-stack" v-loading="loading"><header class="page-heading"><div><p class="eyebrow">Personal credential</p><h1>我的虚拟 API Key</h1><p>一把个人 Key 可调用所有已授权模型；请求中通过 <code>model</code> 参数选择模型。</p></div></header><section class="surface" v-if="defaultMemberId"><div class="detail-metrics"><span>可用 Token<strong>{{ quota?.availableTokens ?? 0 }}</strong></span><span>已消费<strong>{{ quota?.consumedTokens ?? 0 }}</strong></span><span>冻结<strong>{{ quota?.frozenTokens ?? 0 }}</strong></span></div><el-alert v-if="status?.reissueRequired" type="warning" :closable="false" title="旧应用绑定 Key 已停用，请重新生成个人 Key。"/><div class="detail-block"><h3>Key 状态</h3><p v-if="status?.enabled">当前 Key 前缀：<code>{{ status.keyPrefix }}</code></p><p v-else class="muted">尚无可用 Key。确认已有额度与模型权限后即可生成。</p><el-button v-if="!status?.enabled" type="primary" @click="generate()">{{ status?.reissueRequired ? '重新生成 Key' : '生成 Key' }}</el-button><el-button v-else @click="generate(true)">轮换 Key</el-button></div></section><section v-else class="surface empty-state"><h2>请选择开发成员</h2><p>开发期角色视角仅展示当前成员自己的 Key。</p></section></section>
</template>
