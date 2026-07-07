import { createMockApi } from './mock-data';

export interface SessionSummary {
  totalReviews: number;
  completedReviews: number;
  failedReviews: number;
  totalCost: number;
  topModel: string;
}

export interface CostData {
  period: string;
  totalCost: number;
  byModel: { model: string; count: number; cost: number }[];
}

export interface TokenData {
  period: string;
  totalTokens: number;
  byModel: {
    model: string;
    count: number;
    inputTokens: number;
    outputTokens: number;
    totalTokens: number;
  }[];
}

export interface SessionListItem {
  id: number;
  repository: string;
  prNumber: number;
  prTitle: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  /** True when the model had no pricing entry, so the $0 cost is unknown rather than free. */
  pricingMissing: boolean;
  durationMs: number;
  criticalFindings: number;
  highFindings: number;
  mediumFindings: number;
  lowFindings: number;
  status: string;
  timestamp: string;
}

export interface SessionDetail extends SessionListItem {
  commitSha: string;
  errorMessage?: string | null;
  aiResponseJson?: string | null;
}

const BASE = '';

async function fetchJson<T>(url: string): Promise<T> {
  const res = await fetch(BASE + url, { credentials: 'include' });
  if (res.status === 401) {
    // Session expired or invalid: send the user back to the login page instead of
    // leaving the dashboard stuck on empty cards or a permanent loading state
    if (typeof window !== 'undefined') window.location.href = '/dashboard/';
    throw new Error('Session expired');
  }
  if (!res.ok) throw new Error(`API error: ${res.status}`);
  return res.json();
}

function realApi() {
  return {
    summary: () => fetchJson<SessionSummary>('/api/dashboard/summary'),
    costs: (period = 'month') => fetchJson<CostData>(`/api/dashboard/costs?period=${period}`),
    tokens: (period = 'month') => fetchJson<TokenData>(`/api/dashboard/tokens?period=${period}`),
    sessions: (page = 0, repo?: string) =>
      fetchJson<{ sessions: SessionListItem[]; total: number; page: number; size: number }>(
        `/api/dashboard/sessions?page=${page}&size=20${repo ? `&repository=${repo}` : ''}`,
      ),
    session: (id: number) => fetchJson<SessionDetail>(`/api/dashboard/sessions/${id}`),
  };
}

export function api() {
  if (process.env.NEXT_PUBLIC_MOCK_API === 'true') {
    return createMockApi();
  }
  return realApi();
}
