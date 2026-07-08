import { describe, expect, it } from 'vitest';
import type { SessionEvent } from '@/hooks/useWebSocket';
import {
  buildLiveReviews,
  liveReviewProgressMessage,
  liveReviewStatusLabel,
} from './live-reviews';

function event(
  type: SessionEvent['type'],
  sessionId: number,
  data: Record<string, unknown> = {},
  timestamp = '2026-07-07T12:00:00.000Z',
): SessionEvent {
  return {
    type,
    sessionId,
    repository: 'devops-thiago/ThrillhouseBot',
    prNumber: 99,
    prTitle: 'Large PR',
    commitSha: 'abc1234',
    timestamp,
    data,
  };
}

describe('buildLiveReviews', () => {
  it('shows batch progress instead of a frozen empty stream', () => {
    const reviews = buildLiveReviews([
      event('review.batch', 42, { batchIndex: 2, batchCount: 4 }, '2026-07-07T12:00:02.000Z'),
      event('review.started', 42, {}, '2026-07-07T12:00:01.000Z'),
    ]);

    expect(reviews).toHaveLength(1);
    expect(reviews[0].status).toBe('batching');
    expect(reviews[0].batchIndex).toBe(2);
    expect(reviews[0].batchCount).toBe(4);
    expect(liveReviewStatusLabel(reviews[0])).toBe('Reviewing batch 2/4');
    expect(liveReviewProgressMessage(reviews[0])).toContain('batch 2 of 4');
  });

  it('returns to streaming when token events arrive on a normal review', () => {
    const reviews = buildLiveReviews([
      event(
        'review.stream',
        7,
        { tail: '{"findings":[]}', totalChars: 16, attempt: 1, maxAttempts: 5 },
        '2026-07-07T12:00:03.000Z',
      ),
      event('review.started', 7, {}, '2026-07-07T12:00:01.000Z'),
    ]);

    expect(reviews[0].status).toBe('streaming');
    expect(reviews[0].totalChars).toBe(16);
  });

  it('drops completed sessions from the live feed', () => {
    const reviews = buildLiveReviews([
      event('review.completed', 5, {}, '2026-07-07T12:00:05.000Z'),
      event('review.batch', 5, { batchIndex: 1, batchCount: 3 }, '2026-07-07T12:00:02.000Z'),
      event('review.started', 5, {}, '2026-07-07T12:00:01.000Z'),
    ]);

    expect(reviews).toHaveLength(0);
  });
});
