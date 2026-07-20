<template>
  <section class="page-stack no-team-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Account status</p>
        <h1>尚未加入团队</h1>
        <p>账号已成功登录，但尚未分配到可用团队，因此暂时不能调用模型或管理资源。</p>
      </div>
    </header>

    <section class="surface no-team-status">
      <div class="no-team-status-heading">
        <span class="no-team-status-mark">—</span>
        <div><h2>等待团队分配</h2><p>平台管理员将你加入启用团队并授予模型额度后，相关工作台会自动开放。</p></div>
      </div>
      <div class="no-team-metrics" aria-label="当前额度为零">
        <article><span>所属团队</span><strong>未分配</strong></article>
        <article><span>可用 Token</span><strong>0</strong></article>
        <article><span>已消费</span><strong>0</strong></article>
        <article><span>冻结</span><strong>0</strong></article>
      </div>
      <el-alert type="warning" :closable="false" title="当前没有可执行的管理或调用操作。请联系平台管理员分配团队成员关系和相应额度。" />
    </section>
  </section>
</template>

<style scoped>
.no-team-page { max-width:980px; }
.no-team-status { overflow:hidden; padding:0; border-top:3px solid #c98836; }
.no-team-status-heading { display:flex; align-items:flex-start; gap:16px; padding:25px 26px 22px; background:linear-gradient(118deg,#fff8ec 0%,#fff 66%,#f6fbfd 100%); }
.no-team-status-mark { display:grid; width:38px; height:38px; place-items:center; border-radius:50%; background:#f5dfba; color:#9b651e; font-size:24px; font-weight:700; }
.no-team-status-heading h2 { margin:0; color:#193148; font-size:20px; }.no-team-status-heading p { margin:7px 0 0; color:#718094; line-height:1.6; }
.no-team-metrics { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; padding:20px 26px; border-top:1px solid #e6edf1; }.no-team-metrics article { padding:14px; border:1px solid #dce7ef; border-radius:10px; background:#fff; }.no-team-metrics span { display:block; color:#718094; font-size:12px; }.no-team-metrics strong { display:block; margin-top:8px; color:#263d50; font-size:19px; }
.no-team-status :deep(.el-alert) { margin:0 26px 26px; width:auto; }
@media (max-width:720px) { .no-team-status-heading { padding:20px; }.no-team-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); padding:16px 20px; }.no-team-status :deep(.el-alert) { margin:0 20px 20px; } }
</style>
