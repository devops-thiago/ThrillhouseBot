import type { SessionEvent } from '@/hooks/useWebSocket';

export interface LiveReviewState {
  sessionId: number;
  repository: string;
  prNumber: number;
  prTitle: string;
  status: 'streaming' | 'batching' | 'retrying' | 'stream_failed' | 'completed' | 'failed';
  attempt: number;
  maxAttempts: number;
  tail: string;
  totalChars: number;
  batchIndex?: number;
  batchCount?: number;
  reason?: string;
  updatedAt: string;
}

export function buildLiveReviews(events: SessionEvent[]): LiveReviewState[] {
  const bySession = new Map<number, LiveReviewState>();

  for (const event of [...events].reverse()) {
    const data = event.data ?? {};
    const attempt = Number(data.attempt ?? 1);
    const maxAttempts = Number(data.maxAttempts ?? 5);

    if (event.type === 'review.started') {
      bySession.set(event.sessionId, {
        sessionId: event.sessionId,
        repository: event.repository,
        prNumber: event.prNumber,
        prTitle: event.prTitle,
        status: 'streaming',
        attempt: 1,
        maxAttempts,
        tail: '',
        totalChars: 0,
        updatedAt: event.timestamp,
      });
      continue;
    }

    const current = bySession.get(event.sessionId) ?? createLiveReviewFromEvent(event);
    if (!bySession.has(event.sessionId)) {
      bySession.set(event.sessionId, current);
    }

    if (event.type === 'review.stream') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'streaming',
        attempt,
        maxAttempts,
        tail: String(data.tail ?? current.tail),
        totalChars: Number(data.totalChars ?? current.totalChars),
        updatedAt: event.timestamp,
      });
      continue;
    }

    if (event.type === 'review.batch') {
      const batchIndex = Number(data.batchIndex ?? 0);
      const batchCount = Number(data.batchCount ?? 0);
      bySession.set(event.sessionId, {
        ...current,
        status: 'batching',
        batchIndex,
        batchCount,
        updatedAt: event.timestamp,
      });
      continue;
    }

    if (event.type === 'review.retry') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'retrying',
        attempt,
        maxAttempts,
        reason: String(data.reason ?? ''),
        tail: '',
        totalChars: 0,
        updatedAt: event.timestamp,
      });
      continue;
    }

    if (event.type === 'review.stream.failed') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'stream_failed',
        attempt,
        maxAttempts,
        reason: String(data.reason ?? ''),
        updatedAt: event.timestamp,
      });
      continue;
    }

    if (event.type === 'review.completed') {
      bySession.delete(event.sessionId);
      continue;
    }

    if (event.type === 'review.failed') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'failed',
        reason: String(data.errorType ?? 'Review failed'),
        updatedAt: event.timestamp,
      });
    }
  }

  return [...bySession.values()]
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .slice(0, 5);
}

function createLiveReviewFromEvent(event: SessionEvent): LiveReviewState {
  const data = event.data ?? {};
  const attempt = Number(data.attempt ?? 1);
  const maxAttempts = Number(data.maxAttempts ?? 5);

  if (event.type === 'review.stream') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'streaming',
      attempt,
      maxAttempts,
      tail: String(data.tail ?? ''),
      totalChars: Number(data.totalChars ?? 0),
      updatedAt: event.timestamp,
    };
  }

  if (event.type === 'review.batch') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'batching',
      attempt,
      maxAttempts,
      tail: '',
      totalChars: 0,
      batchIndex: Number(data.batchIndex ?? 0),
      batchCount: Number(data.batchCount ?? 0),
      updatedAt: event.timestamp,
    };
  }

  if (event.type === 'review.retry') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'retrying',
      attempt,
      maxAttempts,
      tail: '',
      totalChars: 0,
      reason: String(data.reason ?? ''),
      updatedAt: event.timestamp,
    };
  }

  if (event.type === 'review.stream.failed') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'stream_failed',
      attempt,
      maxAttempts,
      tail: '',
      totalChars: 0,
      reason: String(data.reason ?? ''),
      updatedAt: event.timestamp,
    };
  }

  if (event.type === 'review.failed') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'failed',
      attempt,
      maxAttempts,
      tail: '',
      totalChars: 0,
      reason: String(data.errorType ?? 'Review failed'),
      updatedAt: event.timestamp,
    };
  }

  return {
    sessionId: event.sessionId,
    repository: event.repository,
    prNumber: event.prNumber,
    prTitle: event.prTitle,
    status: 'streaming',
    attempt,
    maxAttempts,
    tail: '',
    totalChars: 0,
    updatedAt: event.timestamp,
  };
}

export function liveReviewStatusLabel(review: LiveReviewState): string {
  if (review.status === 'batching') {
    const index = review.batchIndex ?? 0;
    const count = review.batchCount ?? 0;
    if (count > 0) {
      return `Reviewing batch ${index}/${count}`;
    }
    return 'Reviewing large PR';
  }
  if (review.status === 'streaming') {
    return `Streaming attempt ${review.attempt}/${review.maxAttempts}`;
  }
  if (review.status === 'retrying') {
    return `Retrying attempt ${review.attempt}/${review.maxAttempts}`;
  }
  if (review.status === 'stream_failed') {
    return `Attempt ${review.attempt}/${review.maxAttempts} failed`;
  }
  return 'Review failed';
}

export function liveReviewProgressMessage(review: LiveReviewState): string {
  if (review.status !== 'batching') {
    return 'Waiting for model tokens…';
  }
  const index = review.batchIndex ?? 0;
  const count = review.batchCount ?? 0;
  if (count > 1) {
    return `Large PR review in progress — batch ${index} of ${count}. Live token streaming is disabled between batches.`;
  }
  return 'Large PR review in progress — token streaming is disabled for map-reduce reviews.';
}
