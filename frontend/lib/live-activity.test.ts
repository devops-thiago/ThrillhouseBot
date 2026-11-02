import { describe, expect, it } from 'vitest';
import type { SessionEvent } from '@/hooks/useWebSocket';
import {
  MAX_ENDED_REVIEWS,
  applyActivityEvent,
  formatCost,
  orderedReviews,
  phaseState,
  statusLabel,
  type ActivityState,
} from './live-activity';

let clock = 0;

function at(second: number): string {
  return new Date(Date.UTC(2026, 9, 27, 12, 0, second)).toISOString();
}

/** As the backend sends them: every event of a review carries its start, here one per review. */
function event(
  type: SessionEvent['type'],
  sessionId: number,
  data: Record<string, unknown> = {},
): SessionEvent {
  return {
    type,
    sessionId,
    repository: 'devops-thiago/ThrillhouseBot',
    prNumber: 99,
    prTitle: 'Live activity',
    commitSha: 'abc1234',
    timestamp: at(sessionId),
    data,
  };
}

/** Folds the events as the page receives them, one second apart. */
function fold(events: SessionEvent[], start = new Map<number, ActivityState>()) {
  return events.reduce((reviews, e) => {
    clock += 1;
    return applyActivityEvent(reviews, e, at(100 + clock));
  }, start);
}

describe('applyActivityEvent', () => {
  it('follows a review through its steps, reasoning, output and spend', () => {
    const reviews = fold([
      event('review.started', 1),
      event('review.phase', 1, { phase: 'context' }),
      event('review.phase', 1, { phase: 'review' }),
      event('review.reasoning', 1, { tail: 'Check the null path.', totalChars: 20, attempt: 1 }),
      event('review.stream', 1, { tail: '{"findings":[', totalChars: 13, attempt: 1 }),
      event('review.progress', 1, { inputTokens: 1000, outputTokens: 200, cost: 0.012 }),
    ]);

    const review = reviews.get(1)!;
    expect(review.phases.map((p) => p.phase)).toEqual(['context', 'review']);
    expect(phaseState(review, 'context')).toBe('done');
    expect(phaseState(review, 'review')).toBe('current');
    expect(phaseState(review, 'summarize')).toBe('pending');
    expect(statusLabel(review)).toBe('Review');
    expect(review.reasoningTail).toBe('Check the null path.');
    expect(review.outputChars).toBe(13);
    expect(review.inputTokens + review.outputTokens).toBe(1200);
    expect(formatCost(review)).toBe('$0.0120');
  });

  it('builds the same state again from a reconnect replay', () => {
    const replay = [
      event('review.started', 1),
      event('review.phase', 1, { phase: 'context' }),
      event('review.phase', 1, { phase: 'review' }),
    ];
    const once = fold(replay);
    const twice = fold(replay, once);

    expect(twice.get(1)!.phases.map((p) => p.phase)).toEqual(['context', 'review']);
  });

  it('does not step back for a replay that starts after the review began', () => {
    const live = fold([
      event('review.started', 1),
      event('review.phase', 1, { phase: 'context' }),
      event('review.phase', 1, { phase: 'review' }),
      event('review.phase', 1, { phase: 'summarize' }),
    ]);
    // The server dropped the oldest buffered events, the start and the first step among them.
    const replayed = fold(
      [event('review.phase', 1, { phase: 'review' }), event('review.phase', 1, { phase: 'summarize' })],
      live,
    );

    expect(replayed.get(1)!.phases.map((p) => p.phase)).toEqual(['context', 'review', 'summarize']);
    expect(phaseState(replayed.get(1)!, 'summarize')).toBe('current');
  });

  it('keeps the review that ended last, though it started first', () => {
    const events: SessionEvent[] = [event('review.started', 1)];
    for (let id = 2; id <= MAX_ENDED_REVIEWS + 1; id++) {
      events.push(event('review.started', id), event('review.completed', id));
    }
    events.push(event('review.completed', 1));

    const reviews = fold(events);

    expect(reviews.has(1)).toBe(true);
    expect(reviews.has(2)).toBe(false);
  });

  it('ignores an unknown step and a repeat of the current one', () => {
    const reviews = fold([
      event('review.started', 1),
      event('review.phase', 1, { phase: 'review' }),
      event('review.phase', 1, { phase: 'review' }),
      event('review.phase', 1, { phase: 'triage' }),
    ]);

    expect(reviews.get(1)!.phases.map((p) => p.phase)).toEqual(['review']);
  });

  it('clears the streamed text when an attempt is retried', () => {
    const reviews = fold([
      event('review.started', 1),
      event('review.reasoning', 1, { tail: 'hmm', totalChars: 3 }),
      event('review.stream', 1, { tail: '{', totalChars: 1 }),
      event('review.retry', 1, { attempt: 2, maxAttempts: 5, reason: 'timeout' }),
    ]);

    const review = reviews.get(1)!;
    expect(review.status).toBe('retrying');
    expect(review.reasoningTail).toBe('');
    expect(review.outputTail).toBe('');
    expect(statusLabel(review)).toBe('Retrying, attempt 2/5');
    expect(phaseState({ ...review, phases: [{ phase: 'review', at: '' }] }, 'review')).toBe(
      'current',
    );
  });

  it('keeps a failed stream attempt running until the retry is announced', () => {
    const reviews = fold([
      event('review.started', 1),
      event('review.stream.failed', 1, { attempt: 1, maxAttempts: 3, reason: 'reset' }),
    ]);

    expect(reviews.get(1)!.status).toBe('running');
    expect(reviews.get(1)!.reason).toBe('reset');
  });

  it('shows batch progress', () => {
    const reviews = fold([
      event('review.started', 1),
      event('review.batch', 1, { batchIndex: 2, batchCount: 4 }),
    ]);

    expect(reviews.get(1)!.batchIndex).toBe(2);
    expect(reviews.get(1)!.batchCount).toBe(4);
  });

  it('keeps the final figures of a completed review against a late usage report', () => {
    const reviews = fold([
      event('review.started', 1),
      event('review.phase', 1, { phase: 'publish' }),
      event('review.completed', 1, {
        critical: 0,
        high: 1,
        medium: 2,
        low: 0,
        inputTokens: 5000,
        outputTokens: 90,
        cost: 0.02,
        durationMs: 4200,
      }),
      event('review.progress', 1, { inputTokens: 1, outputTokens: 1, cost: 0.001 }),
    ]);

    const review = reviews.get(1)!;
    expect(review.status).toBe('completed');
    expect(review.findings).toEqual({ critical: 0, high: 1, medium: 2, low: 0 });
    expect(review.cost).toBe(0.02);
    expect(phaseState(review, 'publish')).toBe('done');
    expect(statusLabel(review)).toBe('Completed');
  });

  it('does not bring back, as running, a review whose usage report came after it ended', () => {
    const reviews = fold([event('review.progress', 5, { inputTokens: 1, outputTokens: 1 })]);

    expect(reviews.size).toBe(0);
  });

  it('counts a step a batched review went past as done', () => {
    const reviews = fold([
      event('review.started', 1),
      event('review.phase', 1, { phase: 'review' }),
      event('review.phase', 1, { phase: 'summarize' }),
    ]);

    expect(phaseState(reviews.get(1)!, 'verify')).toBe('done');
    expect(phaseState(reviews.get(1)!, 'publish')).toBe('pending');
  });

  it('records why a review failed', () => {
    const reviews = fold([event('review.started', 1), event('review.failed', 1, { errorType: 'boom' })]);

    expect(reviews.get(1)!.status).toBe('failed');
    expect(reviews.get(1)!.reason).toBe('boom');
    expect(statusLabel(reviews.get(1)!)).toBe('Failed');
  });

  it('starts tracking a review whose start it missed', () => {
    const reviews = fold([event('review.phase', 3, { phase: 'summarize' })]);

    expect(reviews.get(3)!.phases.map((p) => p.phase)).toEqual(['summarize']);
    expect(statusLabel(reviews.get(1) ?? reviews.get(3)!)).toBe('Summarize');
  });

  it('leaves the state alone for a keepalive and an event it does not read', () => {
    const reviews = fold([event('review.started', 1)]);

    expect(applyActivityEvent(reviews, event('keepalive', 0), at(1))).toBe(reviews);
    expect(applyActivityEvent(reviews, event('review.started', 1), at(1))).not.toBe(reviews);
  });

  it(`keeps only the ${MAX_ENDED_REVIEWS} latest reviews that ended`, () => {
    const events: SessionEvent[] = [];
    for (let id = 1; id <= MAX_ENDED_REVIEWS + 2; id++) {
      events.push(event('review.started', id), event('review.completed', id));
    }
    events.push(event('review.started', 100));

    const reviews = fold(events);

    expect(reviews.size).toBe(MAX_ENDED_REVIEWS + 1);
    expect(reviews.has(1)).toBe(false);
    expect(reviews.has(100)).toBe(true);
    expect(orderedReviews(reviews)[0].sessionId).toBe(100);
  });
});

describe('formatCost', () => {
  it('says the cost is unknown when no call had pricing', () => {
    expect(formatCost({ cost: 0, pricingMissing: true })).toBe('unknown');
  });

  it('marks a cost that leaves out unpriced calls as partial', () => {
    expect(formatCost({ cost: 0.5, pricingMissing: true })).toBe('$0.5000 (partial)');
  });
});

describe('statusLabel', () => {
  it('names a retry without a known attempt limit', () => {
    const reviews = fold([event('review.started', 1), event('review.retry', 1, { attempt: 2 })]);
    expect(statusLabel(reviews.get(1)!)).toBe('Retrying, attempt 2');
  });

  it('says a review with no step yet is starting', () => {
    const reviews = fold([event('review.started', 1)]);
    expect(statusLabel(reviews.get(1)!)).toBe('Starting');
  });
});
