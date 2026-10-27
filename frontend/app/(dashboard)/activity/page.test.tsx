import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ActivityPage from './page';
import { useWebSocket, SessionEvent } from '@/hooks/useWebSocket';

vi.mock('@/hooks/useWebSocket', () => ({
  useWebSocket: vi.fn(),
}));

let deliver: (event: SessionEvent) => void = () => {};

function event(
  type: SessionEvent['type'],
  sessionId: number,
  data: Record<string, unknown> = {},
  second = 0,
): SessionEvent {
  return {
    type,
    sessionId,
    repository: 'devops-thiago/ThrillhouseBot',
    prNumber: sessionId,
    prTitle: `PR ${sessionId}`,
    commitSha: 'abc1234',
    timestamp: new Date(Date.UTC(2026, 9, 27, 12, 0, second)).toISOString(),
    data,
  };
}

function send(...events: SessionEvent[]) {
  act(() => events.forEach((e) => deliver(e)));
}

describe('ActivityPage', () => {
  afterEach(() => {
    cleanup();
    window.history.replaceState(null, '', '/');
  });

  beforeEach(() => {
    vi.mocked(useWebSocket).mockImplementation((onEvent) => {
      deliver = onEvent ?? (() => {});
      return { events: [], connected: true };
    });
  });

  it('says no review is running before any event', () => {
    render(<ActivityPage />);

    expect(screen.getByRole('heading', { name: /Live Activity/ })).toBeInTheDocument();
    expect(screen.getByText(/No review is running/)).toBeInTheDocument();
  });

  it('shows the current step, the spend, the reasoning and the output of a running review', () => {
    render(<ActivityPage />);
    send(
      event('review.started', 7, {}, 1),
      event('review.phase', 7, { phase: 'context' }, 2),
      event('review.phase', 7, { phase: 'review' }, 3),
      event('review.reasoning', 7, { tail: 'Check the null path.', totalChars: 20 }, 4),
      event('review.stream', 7, { tail: '{"findings":[', totalChars: 13 }, 5),
      event('review.progress', 7, { inputTokens: 1000, outputTokens: 234, cost: 0.0123 }, 6),
    );

    const steps = screen.getByRole('list', { name: 'Steps' });
    expect(within(steps).getByText(/Read the pull request/).closest('li')).toHaveAttribute(
      'data-state',
      'done',
    );
    expect(within(steps).getByText(/^● Review/).closest('li')).toHaveAttribute(
      'data-state',
      'current',
    );
    expect(screen.getByText(/^1[,.]?234$/)).toBeInTheDocument();
    expect(screen.getByText('$0.0123')).toBeInTheDocument();
    expect(screen.getByText(/Check the null path\./)).toBeInTheDocument();
    expect(screen.getByText(/\{"findings":\[/)).toBeInTheDocument();
  });

  it('explains an empty reasoning panel and a batched review without output', () => {
    render(<ActivityPage />);
    send(
      event('review.started', 7, {}, 1),
      event('review.phase', 7, { phase: 'review' }, 2),
      event('review.batch', 7, { batchIndex: 1, batchCount: 3 }, 3),
    );

    expect(screen.getByText(/DASHBOARD_REASONING_STREAM_ENABLED/)).toBeInTheDocument();
    expect(screen.getByText('(batch 1/3)')).toBeInTheDocument();
    expect(screen.getByText(/does not stream its output/)).toBeInTheDocument();
  });

  it('shows the findings of a completed review and why a review failed', async () => {
    render(<ActivityPage />);
    send(
      event('review.started', 7, {}, 1),
      event('review.completed', 7, { critical: 0, high: 1, medium: 2, low: 3, cost: 0.5 }, 2),
      event('review.started', 8, {}, 3),
      event('review.failed', 8, { errorType: 'Provider down' }, 4),
    );

    await userEvent.click(screen.getByRole('button', { name: /ThrillhouseBot#7/ }));
    expect(screen.getByText('0C / 1H / 2M / 3L')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /ThrillhouseBot#8/ }));
    expect(screen.getByText('Provider down')).toBeInTheDocument();
    expect(window.location.search).toBe('?id=8');
  });

  it('opens the review the link names', () => {
    window.history.replaceState(null, '', '/?id=8');
    render(<ActivityPage />);
    send(event('review.started', 7, {}, 2), event('review.started', 8, {}, 1));

    expect(screen.getByRole('region', { name: 'devops-thiago/ThrillhouseBot#8' })).toBeInTheDocument();
  });

  it('opens the newest running review when the link names none', () => {
    window.history.replaceState(null, '', '/?id=abc');
    render(<ActivityPage />);
    send(event('review.started', 7, {}, 1), event('review.started', 8, {}, 2));

    expect(screen.getByRole('region', { name: 'devops-thiago/ThrillhouseBot#8' })).toBeInTheDocument();
  });
});
