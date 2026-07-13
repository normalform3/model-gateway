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
export interface BootstrapDemoResponse { organizationId: number; teamId: number; applicationId: number; quotaAccountId: number; logicalModel: string }
export interface DemoIdentity { identityId: string; displayName: string; role: "platform-admin" | "team-admin" | "developer"; teamId: number | null; teamName: string | null; memberId: number | null }
export interface DemoIdentityResponse { initialized: boolean; identities: DemoIdentity[] }
export interface PlatformUser { userId: number; name: string; email: string; enabled: boolean; memberId: number | null; teamId: number | null; teamName: string | null; role: "OWNER" | "MEMBER" | null; createdAt: string }
export interface DeploymentItem { deploymentId: number; providerId: number; name: string; actualModel: string; enabled: boolean; inputPricePerMillion: number; outputPricePerMillion: number; currency: string }
export interface RouteTargetItem { deploymentId: number; deploymentName: string; providerName: string; weight: number; enabled: boolean }
export interface LogicalModelItem { logicalModel: string; routeEnabled: boolean; strategy: string; targets: RouteTargetItem[] }
export interface TeamSummary { teamId: number; organizationId: number; defaultApplicationId: number; name: string; enabled: boolean; keyRpm: number; teamRpm: number; teamConcurrency: number; modelConcurrency: number; ownerMemberId: number | null; ownerName: string | null; ownerEmail: string | null; memberCount: number; keyCount: number }
export interface TeamMemberItem { memberId: number; teamId: number; name: string; email: string; role: string; enabled: boolean; createdAt: string }
export interface ApplicationItem { applicationId: number; teamId: number; name: string; createdAt: string }
export interface VirtualApiKeyItem { keyId: number; name: string; keyPrefix: string; teamId: number; teamName: string; applicationId: number; applicationName: string; ownerMemberId: number | null; ownerMemberName: string | null; allowedModels: string[]; enabled: boolean; expiresAt: string | null; createdAt: string }
export interface CreateApiKeyResponse { keyId: number; keyPrefix: string; apiKey: string; enabled: boolean }
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
  assignUserMembership: (userId: number, payload: Record<string, unknown>) => requestJson<PlatformUser>(`/admin/users/${userId}/team-membership`, { method: "PUT", body: JSON.stringify(payload) }),
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
  updateTeam: (teamId: number, payload: Record<string, unknown>) => requestJson<TeamSummary>(`/admin/teams/${teamId}`, { method: "PATCH", body: JSON.stringify(payload) }),
  deleteTeam: (teamId: number) => requestJson<void>(`/admin/teams/${teamId}`, { method: "DELETE" }),
  members: (teamId: number) => requestJson<{ items: TeamMemberItem[] }>(`/admin/teams/${teamId}/members`),
  createMember: (teamId: number, payload: Record<string, unknown>) => requestJson<TeamMemberItem>(`/admin/teams/${teamId}/members`, { method: "POST", body: JSON.stringify(payload) }),
  applications: (teamId: number) => requestJson<{ items: ApplicationItem[] }>(`/admin/teams/${teamId}/applications`),
  createApplication: (teamId: number, payload: Record<string, unknown>) => requestJson<ApplicationItem>(`/admin/teams/${teamId}/applications`, { method: "POST", body: JSON.stringify(payload) }),
  teamModels: (teamId: number) => requestJson<{ teamId: number; logicalModels: string[] }>(`/admin/teams/${teamId}/model-access`),
  updateTeamModels: (teamId: number, logicalModels: string[]) => requestJson<{ teamId: number; logicalModels: string[] }>(`/admin/teams/${teamId}/model-access`, { method: "PUT", body: JSON.stringify({ logicalModels }) }),
  keys: (query?: Query) => requestJson<PageResult<VirtualApiKeyItem>>(withQuery("/admin/api-keys", query)),
  createMemberKey: (teamId: number, memberId: number, payload: Record<string, unknown>) => requestJson<CreateApiKeyResponse>(`/admin/teams/${teamId}/members/${memberId}/api-keys`, { method: "POST", body: JSON.stringify(payload) }),
  disableKey: (keyId: number) => requestJson(`/admin/api-keys/${keyId}/disable`, { method: "POST" }),
  dashboard: () => requestJson<DashboardOverview>("/admin/dashboard/overview"),
  updateRuntimePolicy: (payload: Record<string, unknown>) => requestJson<DashboardOverview>("/admin/dashboard/runtime-policy", { method: "PATCH", body: JSON.stringify(payload) })
};
