export interface BootstrapDemoResponse {
  organizationId: number;
  teamId: number;
  applicationId: number;
  quotaAccountId: number;
  logicalModel: string;
}

export interface CreateApiKeyRequest {
  organizationId: number;
  teamId: number;
  applicationId: number;
  name: string;
  allowedModels: string[];
  expiresAt?: string | null;
}

export interface CreateApiKeyResponse {
  keyId: number;
  keyPrefix: string;
  apiKey: string;
  enabled: boolean;
}

export interface QuotaResponse {
  teamId: number;
  availableTokens: number;
  frozenTokens: number;
  consumedTokens: number;
  updatedAt: string;
}

export interface RequestLogItem {
  requestId: string;
  requestedModel: string;
  actualProvider: string | null;
  actualModel: string | null;
  status: string;
  inputTokens: number;
  outputTokens: number;
  durationMs: number;
  firstTokenMs: number | null;
  createdAt: string;
}

export interface RequestLogResponse {
  items: RequestLogItem[];
  nextCursor: string | null;
}

export interface ChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface ChatCompletionRequest {
  model: string;
  messages: ChatMessage[];
  stream: boolean;
  max_tokens: number;
}

export interface ChatCompletionResponse {
  id: string;
  object: string;
  created: number;
  model: string;
  choices: Array<{
    index: number;
    message?: ChatMessage;
    delta?: Partial<ChatMessage>;
    finish_reason?: string | null;
  }>;
  usage?: {
    prompt_tokens: number;
    completion_tokens: number;
    total_tokens: number;
  };
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly requestId?: string;
  readonly retryable?: boolean;

  constructor(message: string, status: number, code?: string, requestId?: string, retryable?: boolean) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.retryable = retryable;
  }
}

async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init.headers ?? {})
    }
  });

  if (!response.ok) {
    throw await toApiError(response);
  }

  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = await response.json();
    const error = body.error ?? {};
    return new ApiError(
      error.message ?? `HTTP ${response.status}`,
      response.status,
      error.code,
      error.requestId,
      error.retryable
    );
  } catch {
    return new ApiError(`HTTP ${response.status}`, response.status);
  }
}

export function bootstrapDemo(): Promise<BootstrapDemoResponse> {
  return requestJson<BootstrapDemoResponse>("/admin/bootstrap/demo", { method: "POST" });
}

export function createApiKey(payload: CreateApiKeyRequest): Promise<CreateApiKeyResponse> {
  return requestJson<CreateApiKeyResponse>("/admin/api-keys", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function fetchQuota(teamId: number): Promise<QuotaResponse> {
  return requestJson<QuotaResponse>(`/admin/teams/${teamId}/quota`);
}

export function fetchRequestLogs(applicationId: number): Promise<RequestLogResponse> {
  return requestJson<RequestLogResponse>(`/admin/applications/${applicationId}/requests`);
}

export function completeChat(apiKey: string, payload: ChatCompletionRequest): Promise<ChatCompletionResponse> {
  return requestJson<ChatCompletionResponse>("/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      Accept: "application/json",
      "Idempotency-Key": `console-${crypto.randomUUID()}`
    },
    body: JSON.stringify(payload)
  });
}

export async function streamChat(
  apiKey: string,
  payload: ChatCompletionRequest,
  onChunk: (content: string) => void
): Promise<void> {
  const response = await fetch("/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      Accept: "text/event-stream",
      "Idempotency-Key": `console-${crypto.randomUUID()}`
    },
    body: JSON.stringify({ ...payload, stream: true })
  });

  if (!response.ok) {
    throw await toApiError(response);
  }
  if (!response.body) {
    throw new ApiError("SSE response body is empty.", response.status);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split(/\n\n/);
    buffer = events.pop() ?? "";

    for (const event of events) {
      parseSseEvent(event, onChunk);
    }
  }

  if (buffer.trim()) {
    parseSseEvent(buffer, onChunk);
  }
}

function parseSseEvent(raw: string, onChunk: (content: string) => void): void {
  const data = raw
    .split(/\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trim())
    .join("\n");

  if (!data || data === "[DONE]") {
    return;
  }

  const parsed = JSON.parse(data) as ChatCompletionResponse;
  const content = parsed.choices[0]?.delta?.content ?? parsed.choices[0]?.message?.content ?? "";
  if (content) {
    onChunk(content);
  }
}
