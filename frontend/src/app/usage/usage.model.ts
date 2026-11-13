/** Mirrors api-service's UsageDtos. Costs are estimates in USD. */

export interface QuotaStatus {
  used: number;
  /** null: no limit */
  limit: number | null;
  remaining: number | null;
}

export interface MyQuotas {
  questionsToday: QuotaStatus;
  uploadsToday: QuotaStatus;
  workspaces: QuotaStatus;
  maxDocumentsPerWorkspace: number | null;
  /** Midnight UTC, when the daily counters start again. */
  resetsAt: string;
}

export type UsageKind = 'CHAT' | 'EMBEDDING';

export interface UsageReport {
  from: string;
  to: string;
  currency: string;
  totals: {
    answers: number;
    chatInputTokens: number;
    chatOutputTokens: number;
    embeddingTokens: number;
    estimatedCost: number;
    estimatedTokens: boolean;
  };
  daily: DailyUsage[];
  models: {
    model: string;
    kind: UsageKind;
    inputTokens: number;
    outputTokens: number;
    estimatedCost: number | null;
  }[];
  users: {
    userId: string | null;
    username: string | null;
    displayName: string | null;
    answers: number;
    tokens: number;
    estimatedCost: number;
  }[];
  /** Admin report only; name is null for deleted workspaces. */
  workspaces: {
    workspaceId: string;
    name: string | null;
    answers: number;
    tokens: number;
    estimatedCost: number;
  }[];
  unpricedModels: string[];
}

export interface DailyUsage {
  date: string;
  answers: number;
  chatTokens: number;
  embeddingTokens: number;
  estimatedCost: number;
}
