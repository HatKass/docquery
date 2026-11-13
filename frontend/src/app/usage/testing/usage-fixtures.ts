import { MyQuotas, UsageReport } from '../usage.model';

export const QUOTAS: MyQuotas = {
  questionsToday: { used: 12, limit: 50, remaining: 38 },
  uploadsToday: { used: 0, limit: 20, remaining: 20 },
  workspaces: { used: 1, limit: 3, remaining: 2 },
  maxDocumentsPerWorkspace: 100,
  resetsAt: '2026-11-14T00:00:00Z',
};

export const REPORT: UsageReport = {
  from: '2026-11-12',
  to: '2026-11-13',
  currency: 'USD',
  totals: {
    answers: 3,
    chatInputTokens: 3000,
    chatOutputTokens: 300,
    embeddingTokens: 12000,
    estimatedCost: 0.0042,
    estimatedTokens: false,
  },
  daily: [
    {
      date: '2026-11-12',
      answers: 1,
      chatTokens: 1100,
      embeddingTokens: 12000,
      estimatedCost: 0.003,
    },
    { date: '2026-11-13', answers: 2, chatTokens: 2200, embeddingTokens: 0, estimatedCost: 0.0012 },
  ],
  models: [
    {
      model: 'gpt-4.1-mini',
      kind: 'CHAT',
      inputTokens: 3000,
      outputTokens: 300,
      estimatedCost: 0.0017,
    },
  ],
  users: [
    {
      userId: 'u1',
      username: 'owner',
      displayName: 'Olivia Owner',
      answers: 3,
      tokens: 15300,
      estimatedCost: 0.0042,
    },
  ],
  workspaces: [],
  unpricedModels: ['mystery-model'],
};
