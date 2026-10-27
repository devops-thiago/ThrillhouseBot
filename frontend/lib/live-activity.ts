import type { SessionEvent } from '@/hooks/useWebSocket';

/** The steps a review takes, in order, as `review.phase` events name them (#66). */
export const PHASES = [
  { id: 'context', label: 'Read the pull request' },
  { id: 'review', label: 'Review' },
  { id: 'verify', label: 'Check findings' },
  { id: 'summarize', label: 'Summarize' },
  { id: 'publish', label: 'Publish' },
] as const;

export type PhaseId = (typeof PHASES)[number]['id'];

export interface ActivityState {
  sessionId: number;
  repository: string;
  prNumber: number;
  prTitle: string;
  commitSha: string;
  status: 'running' | 'retrying' | 'completed' | 'failed';
  /** The steps reached so far, with when each began. */
  phases: { phase: PhaseId; at: string }[];
  attempt: number;
  maxAttempts: number;
  reason?: string;
  batchIndex?: number;
  batchCount?: number;
  reasoningTail: string;
  reasoningChars: number;
  outputTail: string;
  outputChars: number;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  pricingMissing: boolean;
  findings?: { critical: number; high: number; medium: number; low: number };
  durationMs?: number;
  startedAt: string;
  updatedAt: string;
}

/** Reviews kept on the page once they end, so a finished one can still be read. */
export const MAX_ENDED_REVIEWS = 10;

const PHASE_IDS = new Set<string>(PHASES.map((p) => p.id));

function fresh(event: SessionEvent): ActivityState {
  return {
    sessionId: event.sessionId,
    repository: event.repository,
    prNumber: event.prNumber,
    prTitle: event.prTitle,
    commitSha: event.commitSha,
    status: 'running',
    phases: [],
    attempt: 1,
    maxAttempts: 0,
    reasoningTail: '',
    reasoningChars: 0,
    outputTail: '',
    outputChars: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: 0,
    pricingMissing: false,
    startedAt: event.timestamp,
    updatedAt: event.timestamp,
  };
}

/**
 * Folds one event into the reviews the page shows. Events arrive oldest first; a reconnect replays
 * an active review's buffered events, which fold to the same state again.
 */
export function applyActivityEvent(
  reviews: Map<number, ActivityState>,
  event: SessionEvent,
): Map<number, ActivityState> {
  if (event.type === 'keepalive') {
    return reviews;
  }
  const current = reviews.get(event.sessionId);
  // A usage report can land after the review ended: it neither changes the final figures nor
  // brings back, as running, a review this page never saw start.
  if (event.type === 'review.progress' && !current) {
    return reviews;
  }
  if (current && current.status !== 'running' && current.status !== 'retrying' && event.type !== 'review.started') {
    return reviews;
  }
  const base = event.type === 'review.started' || !current ? fresh(event) : current;
  const next = reduce(base, event);
  if (next === current) {
    return reviews;
  }
  const updated = new Map(reviews);
  updated.set(event.sessionId, { ...next, updatedAt: event.timestamp });
  return pruneEnded(updated);
}

function reduce(review: ActivityState, event: SessionEvent): ActivityState {
  const data = event.data ?? {};
  switch (event.type) {
    case 'review.started':
      return review;
    case 'review.phase': {
      const phase = String(data.phase ?? '');
      if (!PHASE_IDS.has(phase) || review.phases.at(-1)?.phase === phase) {
        return review;
      }
      return {
        ...review,
        status: 'running',
        phases: [...review.phases, { phase: phase as PhaseId, at: event.timestamp }],
      };
    }
    case 'review.batch':
      return {
        ...review,
        batchIndex: Number(data.batchIndex ?? 0),
        batchCount: Number(data.batchCount ?? 0),
      };
    case 'review.reasoning':
      return {
        ...review,
        status: 'running',
        attempt: Number(data.attempt ?? review.attempt),
        reasoningTail: String(data.tail ?? ''),
        reasoningChars: Number(data.totalChars ?? 0),
      };
    case 'review.stream':
      return {
        ...review,
        status: 'running',
        attempt: Number(data.attempt ?? review.attempt),
        outputTail: String(data.tail ?? ''),
        outputChars: Number(data.totalChars ?? 0),
      };
    case 'review.retry':
    case 'review.stream.failed':
      return {
        ...review,
        status: event.type === 'review.retry' ? 'retrying' : review.status,
        attempt: Number(data.attempt ?? review.attempt),
        maxAttempts: Number(data.maxAttempts ?? review.maxAttempts),
        reason: String(data.reason ?? ''),
        reasoningTail: '',
        reasoningChars: 0,
        outputTail: '',
        outputChars: 0,
      };
    case 'review.progress':
      return {
        ...review,
        inputTokens: Number(data.inputTokens ?? review.inputTokens),
        outputTokens: Number(data.outputTokens ?? review.outputTokens),
        cost: Number(data.cost ?? review.cost),
        pricingMissing: Boolean(data.pricingMissing ?? review.pricingMissing),
      };
    case 'review.completed':
      return {
        ...review,
        status: 'completed',
        inputTokens: Number(data.inputTokens ?? review.inputTokens),
        outputTokens: Number(data.outputTokens ?? review.outputTokens),
        cost: Number(data.cost ?? review.cost),
        durationMs: Number(data.durationMs ?? 0),
        findings: {
          critical: Number(data.critical ?? 0),
          high: Number(data.high ?? 0),
          medium: Number(data.medium ?? 0),
          low: Number(data.low ?? 0),
        },
      };
    case 'review.failed':
      return {
        ...review,
        status: 'failed',
        reason: String(data.errorType ?? 'Review failed'),
        durationMs: Number(data.durationMs ?? 0),
      };
    default:
      return review;
  }
}

function pruneEnded(reviews: Map<number, ActivityState>): Map<number, ActivityState> {
  const ended = [...reviews.values()]
    .filter((r) => r.status === 'completed' || r.status === 'failed')
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  for (const review of ended.slice(MAX_ENDED_REVIEWS)) {
    reviews.delete(review.sessionId);
  }
  return reviews;
}

/** Running reviews first, then the ones that ended, each newest first. */
export function orderedReviews(reviews: Map<number, ActivityState>): ActivityState[] {
  const live = (r: ActivityState) => (r.status === 'running' || r.status === 'retrying' ? 0 : 1);
  return [...reviews.values()].sort(
    (a, b) => live(a) - live(b) || b.startedAt.localeCompare(a.startedAt),
  );
}

/**
 * Where a step stands: done, the current one, or not reached. A step the review went past without
 * announcing counts as done: a review in batches checks each batch's findings as it finishes, so
 * it has no separate `verify` step.
 */
export function phaseState(review: ActivityState, phase: PhaseId): 'done' | 'current' | 'pending' {
  const reached = review.phases.findIndex((p) => p.phase === phase);
  if (reached === -1) {
    const latest = review.phases.at(-1)?.phase;
    const order = (id: PhaseId | undefined) => PHASES.findIndex((p) => p.id === id);
    return latest !== undefined && order(phase) < order(latest) ? 'done' : 'pending';
  }
  const last = reached === review.phases.length - 1;
  return last && (review.status === 'running' || review.status === 'retrying') ? 'current' : 'done';
}

/** The review's status in a few words: its current step while it runs. */
export function statusLabel(review: ActivityState): string {
  switch (review.status) {
    case 'retrying':
      return review.maxAttempts > 0
        ? `Retrying, attempt ${review.attempt}/${review.maxAttempts}`
        : `Retrying, attempt ${review.attempt}`;
    case 'completed':
      return 'Completed';
    case 'failed':
      return 'Failed';
    default: {
      const current = review.phases.at(-1)?.phase;
      return PHASES.find((p) => p.id === current)?.label ?? 'Starting';
    }
  }
}

export function formatCost(review: Pick<ActivityState, 'cost' | 'pricingMissing'>): string {
  if (review.pricingMissing && review.cost === 0) {
    return 'unknown';
  }
  return `$${review.cost.toFixed(4)}${review.pricingMissing ? ' (partial)' : ''}`;
}
