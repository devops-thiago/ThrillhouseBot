import type {
  CostData,
  SessionDetail,
  SessionListItem,
  SessionSummary,
  TokenData,
} from './api';

export const MOCK_MODE = process.env.NEXT_PUBLIC_MOCK_API === 'true';

export const mockUser = {
  login: 'milhouse',
  name: 'Milhouse Van Houten',
  avatarUrl: 'https://avatars.githubusercontent.com/u/9919?s=64',
};

export const mockSummary: SessionSummary = {
  totalReviews: 42,
  completedReviews: 38,
  failedReviews: 4,
  totalCost: 0.1284,
  topModel: 'deepseek-chat',
};

export const mockCostData: CostData = {
  period: 'month',
  totalCost: 0.084512,
  byModel: [
    { model: 'deepseek-chat', count: 28, cost: 0.0512 },
    { model: 'gpt-4o-mini', count: 10, cost: 0.033312 },
  ],
};

export const mockTokenData: TokenData = {
  period: 'month',
  totalTokens: 184_320,
  byModel: [
    {
      model: 'deepseek-chat',
      count: 28,
      inputTokens: 120_000,
      outputTokens: 40_000,
      totalTokens: 160_000,
    },
    {
      model: 'gpt-4o-mini',
      count: 10,
      inputTokens: 18_000,
      outputTokens: 6_320,
      totalTokens: 24_320,
    },
  ],
};

export const mockSessions: SessionListItem[] = [
  {
    id: 1,
    repository: 'devops-thiago/ThrillhouseBot',
    prNumber: 12,
    prTitle: 'Add dashboard charts',
    model: 'deepseek-chat',
    inputTokens: 4200,
    outputTokens: 890,
    cost: 0.0012,
    durationMs: 12_400,
    criticalFindings: 0,
    highFindings: 1,
    mediumFindings: 2,
    lowFindings: 0,
    status: 'completed',
    timestamp: '2026-06-12T10:00:00Z',
  },
];

export const mockSessionDetail: SessionDetail = {
  ...mockSessions[0],
  commitSha: 'abc1234',
  aiResponseJson: JSON.stringify({
    findings: [{ severity: 'high', message: 'Example finding' }],
  }),
};

export function mockSessionEvents() {
  const now = new Date().toISOString();
  return [
    {
      type: 'review.started' as const,
      sessionId: 2,
      repository: 'devops-thiago/ThrillhouseBot',
      prNumber: 14,
      prTitle: 'Wire up mock dashboard preview',
      commitSha: 'def5678',
      timestamp: now,
      data: { attempt: 1, maxAttempts: 5 },
    },
    {
      type: 'review.completed' as const,
      sessionId: 1,
      repository: 'devops-thiago/ThrillhouseBot',
      prNumber: 12,
      prTitle: 'Add dashboard charts',
      commitSha: 'abc1234',
      timestamp: now,
      data: { critical: 0, high: 1, medium: 2, low: 0, totalTokens: 5090, cost: 0.0012 },
    },
  ];
}

function delay<T>(value: T, ms = 0): Promise<T> {
  return ms <= 0
    ? Promise.resolve(value)
    : new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

export function createMockApi(ms = 0) {
  return {
    summary: () => delay(mockSummary, ms),
    costs: (period = 'month') =>
      delay({ ...mockCostData, period }, ms),
    tokens: (period = 'month') =>
      delay({ ...mockTokenData, period }, ms),
    sessions: (page = 0, repo?: string) => {
      const sessions = repo
        ? mockSessions.filter((s) => s.repository === repo)
        : mockSessions;
      return delay({ sessions, total: sessions.length, page, size: 20 }, ms);
    },
    session: (id: number) => {
      const found = mockSessions.find((s) => s.id === id);
      if (!found) return Promise.reject(new Error('API error: 404'));
      return delay({ ...mockSessionDetail, ...found, id }, ms);
    },
  };
}
