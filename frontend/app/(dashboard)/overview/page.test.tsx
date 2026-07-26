import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OverviewPage from './page';
import { api } from '@/lib/api';
import { useWebSocket, SessionEvent } from '@/hooks/useWebSocket';
import { mockSummary } from '@/lib/mock-data';

vi.mock('@/lib/api', () => ({
  api: vi.fn(),
}));

vi.mock('@/hooks/useWebSocket', () => ({
  useWebSocket: vi.fn(),
}));

function socket(events: SessionEvent[] = [], connected = true) {
  vi.mocked(useWebSocket).mockReturnValue({ events, connected });
}

function startedEvent(overrides: Partial<SessionEvent> = {}): SessionEvent {
  return {
    type: 'review.started',
    sessionId: 7,
    repository: 'devops-thiago/ThrillhouseBot',
    prNumber: 99,
    prTitle: 'Live streaming PR',
    timestamp: '2026-07-26T12:00:00Z',
    ...overrides,
  } as SessionEvent;
}

describe('OverviewPage', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    socket();
    vi.mocked(api).mockReturnValue({
      summary: vi.fn().mockResolvedValue(mockSummary),
    } as unknown as ReturnType<typeof api>);
  });

  it('renders summary stats once the API resolves', async () => {
    render(<OverviewPage />);

    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument();
    });

    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument();
    expect(screen.getByText('38')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('$0.1284')).toBeInTheDocument();
    expect(screen.getByText('deepseek-chat')).toBeInTheDocument();
  });

  it('shows placeholder values while the summary is still loading', () => {
    // The page keeps stale data rather than swapping in a "Loading..." block, so the
    // pre-resolve state is an em dash in every card — five cards, five placeholders.
    vi.mocked(api).mockReturnValue({
      summary: vi.fn().mockReturnValue(new Promise(() => {})),
    } as unknown as ReturnType<typeof api>);

    render(<OverviewPage />);

    expect(screen.getAllByText('—')).toHaveLength(5);
    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument();
  });

  it('shows an error with retry when the summary fails, then recovers', async () => {
    let calls = 0;
    const summary = vi.fn().mockImplementation(() => {
      calls += 1;
      if (calls === 1) {
        return Promise.reject(new Error('API error: 500'));
      }
      return Promise.resolve(mockSummary);
    });
    vi.mocked(api).mockReturnValue({ summary } as unknown as ReturnType<typeof api>);

    render(<OverviewPage />);

    await waitFor(() => {
      expect(screen.getByText(/Could not load summary metrics: API error: 500/)).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByText('42')).toBeInTheDocument();
    });
    expect(summary.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('reflects websocket connection state in the live output heading', async () => {
    socket([], false);
    render(<OverviewPage />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /Live Model Output 🔴/ })).toBeInTheDocument();
    });
  });

  it('renders a live review and recent activity from a review.started event', async () => {
    socket([startedEvent()]);
    render(<OverviewPage />);

    // One event feeds two sections, so the title appears in the live card and the activity feed.
    await waitFor(() => {
      expect(screen.getAllByText('Live streaming PR')).toHaveLength(2);
    });

    expect(
      screen.getByText('devops-thiago/ThrillhouseBot#99', { selector: 'span' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Review in progress…')).toBeInTheDocument();
    expect(
      screen.queryByText(/No active reviews\. Open a PR to watch the model/),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/No recent reviews\./)).not.toBeInTheDocument();
  });

  it('invites activity when no lifecycle events have arrived', async () => {
    render(<OverviewPage />);

    await waitFor(() => {
      expect(screen.getByText(/No active reviews\./)).toBeInTheDocument();
    });
    expect(screen.getByText(/No recent reviews\./)).toBeInTheDocument();
  });
});
