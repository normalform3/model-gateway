export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly code?: string) {
    super(message);
    this.name = "ApiError";
  }
}

async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init.headers ?? {}) }
  });
  if (!response.ok) {
    try {
      const body = await response.json();
      throw new ApiError(body.error?.message ?? `HTTP ${response.status}`, response.status, body.error?.code);
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(`HTTP ${response.status}`, response.status);
    }
  }
  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export interface ProviderSummary { providerId: number; name: string; providerType: string; baseUrl: string | null; enabled: boolean; updatedAt: string }
export interface ProviderCredential { credentialId: number; providerId: number; name: string; lastFour: string; enabled: boolean; updatedAt: string }
export interface DirectModel { modelId: number; providerId: number; providerName: string; modelName: string; enabled: boolean; inputPricePerMillion: number; outputPricePerMillion: number; currency: string }
export interface BootstrapDemoResponse { organizationId: number; teamId: number; quotaAccountId: number; logicalModel: string }
export interface DemoIdentity { identityId: string; displayName: string; role: "platform-admin" | "team-admin" | "developer"; teamId: number | null; teamName: string | null; memberId: number | null }
export interface DemoIdentityResponse { initialized: boolean; identities: DemoIdentity[] }
export interface PlatformUser { userId: number; name: string; email: string; enabled: boolean; memberId: number | null; teamId: number | null; teamName: string | null; role: "OWNER" | "MEMBER" | null; createdAt: string }
export interface DeploymentItem { deploymentId: number; providerId: number; name: string; actualModel: string; enabled: boolean; inputPricePerMillion: number; outputPricePerMillion: number; currency: string }
export interface RouteTargetItem { deploymentId: number; deploymentName: string; providerName: string; weight: number; enabled: boolean }
export interface LogicalModelItem { logicalModel: string; routeEnabled: boolean; strategy: string; targets: RouteTargetItem[] }
export interface TeamSummary { teamId: number; organizationId: number; name: string; status: "DRAFT" | "READY_FOR_REQUEST" | "ACTIVE" | "SUSPENDED" | "DISSOLVED"; enabled: boolean; keyRpm: number; teamRpm: number; teamConcurrency: number; modelConcurrency: number; ownerMemberId: number | null; ownerName: string | null; ownerEmail: string | null; memberCount: number; keyCount: number }
export interface TeamMemberItem { memberId: number; teamId: number; name: string; email: string; role: string; enabled: boolean; createdAt: string }
export interface TeamMemberCandidate { userId: number; name: string; email: string; previousTeamName: string | null; rejoining: boolean }
export interface VirtualApiKeyItem { keyId: number; name: string; keyPrefix: string; teamId: number; teamName: string; ownerMemberId: number | null; ownerMemberName: string | null; allowedModels: string[]; enabled: boolean; expiresAt: string | null; createdAt: string }
export interface CreateApiKeyResponse { keyId: number; keyPrefix: string; apiKey: string; enabled: boolean }
export interface TeamEntitlement { requestId: number; teamId: number; ownerMemberId: number; modelNames: string[]; requestedTokens: number; grantedModelNames: string[]; grantedTokens: number | null; purpose: string; expiresAt: string | null; status: "PENDING" | "APPROVED" | "REJECTED"; reviewerNote: string | null; createdAt: string; reviewedAt: string | null }
export interface MemberAccess { memberId: number; quotaAccountId: number; availableTokens: number; modelNames: string[] }
export interface MemberKeyStatus { keyId: number | null; keyPrefix: string | null; enabled: boolean; reissueRequired: boolean; createdAt: string | null }
export interface QuotaBalance { availableTokens: number; frozenTokens: number; consumedTokens: number; updatedAt: string }
export interface ProjectItem { projectId: number; teamId: number; name: string; projectCode: string; enabled: boolean; createdAt: string }
export interface ProjectServiceAccountStatus { serviceAccountId: number; projectId: number; name: string; enabled: boolean; createdAt: string; keyId: number | null; keyPrefix: string | null; keyEnabled: boolean; keyCreatedAt: string | null }
export interface ApplicationQuotaOverview { teamId: number; balance: QuotaBalance; modelEntitlements: ModelEntitlement[] }
export interface ProjectApplicationQuotaOverview { projectId: number; balance: QuotaBalance; modelEntitlements: ModelEntitlement[] }
export interface BillingSummary { scopeId: number; totalTokens: number; totalAmount: number; currency: string; recordCount: number }
export type QuotaMode = "DAILY" | "WEEKLY" | "UNLIMITED";
export interface ModelEntitlement { grantId: number; teamId: number; memberId: number | null; modelName: string; quotaMode: QuotaMode; quotaLimit: number | null; status: string; consumedTokens: number; frozenTokens: number; remainingTokens: number | null; cycleStartedAt: string; reason: string; createdAt: string; revokedAt: string | null }
export interface UsageTrend { day: string; tokens: number; amount: number; requests: number }
export interface MemberUsageRank { memberId: number; memberName: string; tokens: number; amount: number }
export interface UsageDashboard { scopeId: number; modelEntitlements: ModelEntitlement[]; lastSevenDays: UsageTrend[]; memberRanking: MemberUsageRank[] }
export interface QuotaSummaryItem { modelName: string; quotaMode: QuotaMode; teamCount: number; allocatedTokens: number; consumedTokens: number; frozenTokens: number; remainingTokens: number }
export interface QuotaSummary { allocatedTokens: number; consumedTokens: number; frozenTokens: number; remainingTokens: number; unlimitedEntitlementCount: number; items: QuotaSummaryItem[] }
export interface DashboardOverview { enabledProviderCount: number; enabledTeamCount: number; enabledKeyCount: number; requestsLast24Hours: number; successfulRequestsLast24Hours: number; throttledRequestsLast24Hours: number; frozenTokens: number; billingAmountLast24Hours: number; billingCurrency: string; globalRpm: number; globalConcurrency: number }
export interface PageResult<T> { items: T[]; page: number; size: number; total: number }
export type Query = Record<string, string | number | boolean | null | undefined>;
function withQuery(path: string, query: Query = {}): string { const params = new URLSearchParams(); Object.entries(query).forEach(([key, value]) => { if (value !== null && value !== undefined && value !== "") params.set(key, String(value)); }); const encoded = params.toString(); return encoded ? `${path}?${encoded}` : path; }

export const api = {
  bootstrapDemo: () => requestJson<BootstrapDemoResponse>("/admin/bootstrap/demo", { method: "POST" }),
  demoIdentities: () => requestJson<DemoIdentityResponse>("/admin/demo-identities"),
  users: (query?: Query) => requestJson<{ items: PlatformUser[] }>(withQuery("/admin/users", query)),
  createUser: (payload: Record<string, unknown>) => requestJson<PlatformUser>("/admin/users", { method: "POST", body: JSON.stringify(payload) }),
  updateUser: (userId: number, payload: Record<string, unknown>) => requestJson<PlatformUser>(`/admin/users/${userId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  deleteUser: (userId: number) => requestJson<void>(`/admin/users/${userId}`, { method: "DELETE" }),
  providers: (query?: Query) => requestJson<PageResult<ProviderSummary>>(withQuery("/admin/providers", query)),
  createProvider: (payload: Record<string, unknown>) => requestJson<ProviderSummary>("/admin/providers", { method: "POST", body: JSON.stringify(payload) }),
  updateProvider: (id: number, payload: Record<string, unknown>) => requestJson<ProviderSummary>(`/admin/providers/${id}`, { method: "PATCH", body: JSON.stringify(payload) }),
  deleteProvider: (id: number) => requestJson<void>(`/admin/providers/${id}`, { method: "DELETE" }),
  credentials: (providerId: number) => requestJson<{ items: ProviderCredential[] }>(`/admin/providers/${providerId}/credentials`),
  createCredential: (providerId: number, payload: Record<string, unknown>) => requestJson<ProviderCredential>(`/admin/providers/${providerId}/credentials`, { method: "POST", body: JSON.stringify(payload) }),
  updateCredential: (credentialId: number, payload: Record<string, unknown>) => requestJson<ProviderCredential>(`/admin/provider-credentials/${credentialId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  disableCredential: (credentialId: number) => requestJson<void>(`/admin/provider-credentials/${credentialId}/disable`, { method: "POST" }),
  directModels: () => requestJson<{ items: DirectModel[] }>("/admin/models"),
  createDirectModel: (payload: Record<string, unknown>) => requestJson<DirectModel>("/admin/models", { method: "POST", body: JSON.stringify(payload) }),
  updateDirectModel: (modelId: number, payload: Record<string, unknown>) => requestJson<DirectModel>(`/admin/models/${modelId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  deleteDirectModel: (modelId: number) => requestJson<void>(`/admin/models/${modelId}`, { method: "DELETE" }),
  deployments: (providerId: number) => requestJson<{ items: DeploymentItem[] }>(`/admin/providers/${providerId}/deployments`),
  createDeployment: (providerId: number, payload: Record<string, unknown>) => requestJson<DeploymentItem>(`/admin/providers/${providerId}/deployments`, { method: "POST", body: JSON.stringify(payload) }),
  updateDeployment: (deploymentId: number, payload: Record<string, unknown>) => requestJson<DeploymentItem>(`/admin/deployments/${deploymentId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  deleteDeployment: (deploymentId: number) => requestJson<void>(`/admin/deployments/${deploymentId}`, { method: "DELETE" }),
  logicalModels: () => requestJson<{ items: LogicalModelItem[] }>("/admin/logical-models"),
  upsertLogicalModel: (payload: Record<string, unknown>) => requestJson<LogicalModelItem>("/admin/logical-models", { method: "POST", body: JSON.stringify(payload) }),
  upsertTarget: (model: string, payload: Record<string, unknown>) => requestJson<LogicalModelItem>(`/admin/logical-models/${encodeURIComponent(model)}/targets`, { method: "POST", body: JSON.stringify(payload) }),
  teams: (query?: Query) => requestJson<PageResult<TeamSummary>>(withQuery("/admin/teams", query)),
  createTeam: (payload: Record<string, unknown>) => requestJson<TeamSummary>("/admin/teams", { method: "POST", body: JSON.stringify(payload) }),
  setTeamOwner: (teamId: number, ownerUserId: number | null) => requestJson<TeamSummary>(`/admin/teams/${teamId}/owner`, { method: "PUT", body: JSON.stringify({ ownerUserId }) }),
  updateTeam: (teamId: number, payload: Record<string, unknown>) => requestJson<TeamSummary>(`/admin/teams/${teamId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  deleteTeam: (teamId: number) => requestJson<void>(`/admin/teams/${teamId}`, { method: "DELETE" }),
  dissolveTeam: (teamId: number) => requestJson<void>(`/admin/teams/${teamId}/dissolve`, { method: "POST" }),
  members: (teamId: number) => requestJson<{ items: TeamMemberItem[] }>(`/admin/teams/${teamId}/members`),
  memberCandidates: (teamId: number) => requestJson<{ items: TeamMemberCandidate[] }>(`/admin/teams/${teamId}/member-candidates`),
  addPlatformMember: (teamId: number, userId: number) => requestJson<TeamMemberItem>(`/admin/teams/${teamId}/members`, { method: "POST", body: JSON.stringify({ userId }) }),
  addExistingMember: (teamId: number, ownerMemberId: number, userId: number) => requestJson<TeamMemberItem>(`/admin/teams/${teamId}/members/from-user`, { method: "POST", body: JSON.stringify({ ownerMemberId, userId }) }),
  deactivateMember: (teamId: number, memberId: number) => requestJson<void>(`/admin/teams/${teamId}/members/${memberId}`, { method: "PATCH", body: JSON.stringify({ enabled: false }) }),
  deactivateOwnedMember: (teamId: number, memberId: number, ownerMemberId: number) => requestJson<void>(`/admin/teams/${teamId}/members/${memberId}/from-owner`, { method: "PATCH", body: JSON.stringify({ ownerMemberId, enabled: false }) }),
  teamModels: (teamId: number) => requestJson<{ teamId: number; logicalModels: string[] }>(`/admin/teams/${teamId}/model-access`),
  teamEntitlementRequests: (teamId: number) => requestJson<{ items: TeamEntitlement[] }>(`/admin/teams/${teamId}/entitlement-requests`),
  requestTeamEntitlement: (teamId: number, payload: Record<string, unknown>) => requestJson<TeamEntitlement>(`/admin/teams/${teamId}/entitlement-requests`, { method: "POST", body: JSON.stringify(payload) }),
  entitlementRequests: (status = "PENDING") => requestJson<{ items: TeamEntitlement[] }>(withQuery("/admin/entitlement-requests", { status })),
  grantTeamEntitlement: (teamId: number, payload: Record<string, unknown>) => requestJson<void>(`/admin/teams/${teamId}/entitlements`, { method: "POST", body: JSON.stringify(payload) }),
  reviewTeamEntitlement: (requestId: number, payload: Record<string, unknown>) => requestJson<TeamEntitlement>(`/admin/entitlement-requests/${requestId}/review`, { method: "POST", body: JSON.stringify(payload) }),
  grantMemberAccess: (teamId: number, memberId: number, payload: Record<string, unknown>) => requestJson<MemberAccess>(`/admin/teams/${teamId}/members/${memberId}/access`, { method: "POST", body: JSON.stringify(payload) }),
  memberKeyStatus: (memberId: number) => requestJson<MemberKeyStatus>(`/admin/members/${memberId}/api-key-status`),
  generateMemberKey: (memberId: number) => requestJson<CreateApiKeyResponse>(`/admin/members/${memberId}/api-keys/generate`, { method: "POST" }),
  rotateMemberKey: (memberId: number) => requestJson<CreateApiKeyResponse>(`/admin/members/${memberId}/api-keys/rotate`, { method: "POST" }),
  teamQuota: (teamId: number) => requestJson<QuotaBalance>(`/admin/teams/${teamId}/quota`),
  memberQuota: (memberId: number) => requestJson<QuotaBalance>(`/admin/members/${memberId}/quota`),
  teamApplicationQuota: (teamId: number) => requestJson<ApplicationQuotaOverview>(`/admin/teams/${teamId}/application-quota`),
  projects: (teamId: number) => requestJson<{ items: ProjectItem[] }>(`/admin/teams/${teamId}/projects`),
  createProject: (teamId: number, payload: Record<string, unknown>) => requestJson<ProjectItem>(`/admin/teams/${teamId}/projects`, { method: "POST", body: JSON.stringify(payload) }),
  updateProject: (teamId: number, projectId: number, payload: Record<string, unknown>) => requestJson<ProjectItem>(`/admin/teams/${teamId}/projects/${projectId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  grantTeamApplicationPool: (teamId: number, payload: Record<string, unknown>) => requestJson<void>(`/admin/teams/${teamId}/application-entitlements`, { method: "POST", body: JSON.stringify(payload) }),
  upsertTeamApplicationModel: (teamId: number, modelName: string, payload: Record<string, unknown>) => requestJson<QuotaBalance>(`/admin/teams/${teamId}/application-entitlements/${encodeURIComponent(modelName)}`, { method: "PUT", body: JSON.stringify(payload) }),
  deleteTeamApplicationModel: (teamId: number, modelName: string) => requestJson<QuotaBalance>(`/admin/teams/${teamId}/application-entitlements/${encodeURIComponent(modelName)}`, { method: "DELETE" }),
  projectApplicationQuota: (teamId: number, projectId: number) => requestJson<ProjectApplicationQuotaOverview>(`/admin/teams/${teamId}/projects/${projectId}/application-quota`),
  allocateProjectQuota: (teamId: number, projectId: number, payload: Record<string, unknown>) => requestJson<{ projectId: number; quotaAccountId: number; availableTokens: number; modelNames: string[] }>(`/admin/teams/${teamId}/projects/${projectId}/quota-allocations`, { method: "POST", body: JSON.stringify(payload) }),
  projectServiceAccounts: (teamId: number, projectId: number) => requestJson<{ items: ProjectServiceAccountStatus[] }>(`/admin/teams/${teamId}/projects/${projectId}/service-accounts`),
  createProjectServiceAccount: (teamId: number, projectId: number, payload: Record<string, unknown>) => requestJson<ProjectServiceAccountStatus>(`/admin/teams/${teamId}/projects/${projectId}/service-accounts`, { method: "POST", body: JSON.stringify(payload) }),
  updateProjectServiceAccount: (teamId: number, projectId: number, serviceAccountId: number, payload: Record<string, unknown>) => requestJson<ProjectServiceAccountStatus>(`/admin/teams/${teamId}/projects/${projectId}/service-accounts/${serviceAccountId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  generateApplicationKey: (serviceAccountId: number) => requestJson<CreateApiKeyResponse>(`/admin/project-service-accounts/${serviceAccountId}/api-keys/generate`, { method: "POST" }),
  rotateApplicationKey: (serviceAccountId: number) => requestJson<CreateApiKeyResponse>(`/admin/project-service-accounts/${serviceAccountId}/api-keys/rotate`, { method: "POST" }),
  teamBillingSummary: (teamId: number) => requestJson<BillingSummary>(`/admin/teams/${teamId}/billing-summary`),
  memberBillingSummary: (memberId: number) => requestJson<BillingSummary>(`/admin/members/${memberId}/billing-summary`),
  teamModelEntitlements: (teamId: number) => requestJson<{ items: ModelEntitlement[] }>(`/admin/teams/${teamId}/model-entitlements`),
  upsertTeamModelEntitlement: (teamId: number, model: string, payload: Record<string, unknown>) => requestJson<ModelEntitlement>(`/admin/teams/${teamId}/model-entitlements/${encodeURIComponent(model)}`, { method: "PUT", body: JSON.stringify(payload) }),
  revokeTeamModelEntitlement: (teamId: number, model: string) => requestJson<void>(`/admin/teams/${teamId}/model-entitlements/${encodeURIComponent(model)}`, { method: "DELETE" }),
  memberModelEntitlements: (teamId: number, memberId: number) => requestJson<{ items: ModelEntitlement[] }>(`/admin/teams/${teamId}/members/${memberId}/model-entitlements`),
  upsertMemberModelEntitlement: (teamId: number, memberId: number, model: string, payload: Record<string, unknown>) => requestJson<ModelEntitlement>(`/admin/teams/${teamId}/members/${memberId}/model-entitlements/${encodeURIComponent(model)}`, { method: "PUT", body: JSON.stringify(payload) }),
  revokeMemberModelEntitlement: (teamId: number, memberId: number, model: string, ownerMemberId: number) => requestJson<void>(withQuery(`/admin/teams/${teamId}/members/${memberId}/model-entitlements/${encodeURIComponent(model)}`, { ownerMemberId }), { method: "DELETE" }),
  teamUsageDashboard: (teamId: number) => requestJson<UsageDashboard>(`/admin/teams/${teamId}/usage-dashboard`),
  memberUsageDashboard: (memberId: number) => requestJson<UsageDashboard>(`/admin/members/${memberId}/usage-dashboard`),
  quotaSummary: () => requestJson<QuotaSummary>("/admin/dashboard/quota-summary"),
  keys: (query?: Query) => requestJson<PageResult<VirtualApiKeyItem>>(withQuery("/admin/api-keys", query)),
  disableKey: (keyId: number) => requestJson(`/admin/api-keys/${keyId}/disable`, { method: "POST" }),
  dashboard: () => requestJson<DashboardOverview>("/admin/dashboard/overview"),
  updateRuntimePolicy: (payload: Record<string, unknown>) => requestJson<DashboardOverview>("/admin/dashboard/runtime-policy", { method: "PATCH", body: JSON.stringify(payload) })
};
