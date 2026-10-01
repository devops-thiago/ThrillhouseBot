import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import SessionsPage from './page';
import { api, ApiError } from '@/lib/api';
import { mockSessionDetail, mockSessions } from '@/lib/mock-data';

vi.mock('@/lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api')>()),
  api: vi.fn(),
}));

function listPage(overrides: Partial<{ total: number; page: number }> = {}) {
  return {
    sessions: mockSessions,
    total: overrides.total ?? mockSessions.length,
    page: overrides.page ?? 0,
    size: 20,
  };
}

describe('SessionsPage', () => {
  afterEach(() => {
    cleanup();
    window.history.replaceState(null, '', '/');
  });

  beforeEach(() => {
    vi.mocked(api).mockReturnValue({
      sessions: vi.fn().mockResolvedValue(listPage()),
      session: vi.fn().mockResolvedValue(mockSessionDetail),
    } as unknown as ReturnType<typeof api>);
  });

  it('renders session rows once the list API resolves', async () => {
    render(<SessionsPage />);

    expect(screen.getByText('Loading...')).toBeInTheDocument();

    await screen.findByText('#12');

    expect(screen.getByText('devops-thiago/ThrillhouseBot')).toBeInTheDocument();
    expect(screen.getByText('deepseek-chat')).toBeInTheDocument();
  });

  it('shows an error with retry when the list API fails, then recovers', async () => {
    let calls = 0;
    const sessions = vi.fn().mockImplementation(() => {
      calls += 1;
      if (calls === 1) {
        return Promise.reject(new Error('API error: 500'));
      }
      return Promise.resolve(listPage());
    });
    vi.mocked(api).mockReturnValue({
      sessions,
      session: vi.fn().mockResolvedValue(mockSessionDetail),
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);

    await waitFor(() => {
      expect(screen.getByText(/API error: 500/)).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByText('#12')).toBeInTheDocument();
    });
    expect(sessions.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('loads session detail when a row is selected and reflects it in the URL', async () => {
    const session = vi.fn().mockResolvedValue(mockSessionDetail);
    vi.mocked(api).mockReturnValue({
      sessions: vi.fn().mockResolvedValue(listPage()),
      session,
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);
    await waitFor(() => {
      expect(screen.getByText('#12')).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole('button', { name: 'View' }));

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Session #1' })).toBeInTheDocument();
    });
    expect(session).toHaveBeenCalledWith(1);
    // Selecting deep-links the row so the panel survives a reload/share.
    expect(new URLSearchParams(window.location.search).get('id')).toBe('1');

    const panel = screen.getByRole('heading', { name: 'Session #1' }).parentElement!.parentElement!;
    expect(within(panel).getByText('abc1234')).toBeInTheDocument();
  });

  it('opens the detail panel straight from an ?id= deep link', async () => {
    window.history.replaceState(null, '', '/?id=1');
    const session = vi.fn().mockResolvedValue(mockSessionDetail);
    vi.mocked(api).mockReturnValue({
      sessions: vi.fn().mockResolvedValue(listPage()),
      session,
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Session #1' })).toBeInTheDocument();
    });
    expect(session).toHaveBeenCalledWith(1);
  });

  it('ignores a non-numeric ?id= rather than requesting it', async () => {
    window.history.replaceState(null, '', '/?id=not-a-number');
    const session = vi.fn().mockResolvedValue(mockSessionDetail);
    vi.mocked(api).mockReturnValue({
      sessions: vi.fn().mockResolvedValue(listPage()),
      session,
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);

    await waitFor(() => {
      expect(screen.getByText('#12')).toBeInTheDocument();
    });
    expect(screen.queryByRole('heading', { name: /^Session #/ })).not.toBeInTheDocument();
    expect(session).not.toHaveBeenCalled();
  });

  it('reports a detail fetch failure inside the panel', async () => {
    vi.mocked(api).mockReturnValue({
      sessions: vi.fn().mockResolvedValue(listPage()),
      session: vi.fn().mockRejectedValue(new Error('detail boom')),
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);
    await waitFor(() => {
      expect(screen.getByText('#12')).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole('button', { name: 'View' }));

    await waitFor(() => {
      expect(screen.getByText('Could not load session details.')).toBeInTheDocument();
    });
  });

  it('shows a not-found message, and nothing of the session, when the API answers 404', async () => {
    // The API answers 404 for a session in a repository this login cannot read.
    window.history.replaceState(null, '', '/?id=77');
    vi.mocked(api).mockReturnValue({
      sessions: vi.fn().mockResolvedValue({ sessions: [], total: 0, page: 0, size: 20 }),
      session: vi.fn().mockRejectedValue(new ApiError(404)),
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);

    await waitFor(() => {
      expect(
        screen.getByText(
          'Session not found, or it belongs to a repository you do not have access to.',
        ),
      ).toBeInTheDocument();
    });
    expect(screen.queryByText('Repository')).not.toBeInTheDocument();
    expect(screen.queryByText('Model output')).not.toBeInTheDocument();
  });

  it('renders exactly the rows and total the API scoped to the login', async () => {
    const own = mockSessions.filter((s) => s.repository === 'devops-thiago/ThrillhouseBot');
    vi.mocked(api).mockReturnValue({
      sessions: vi.fn().mockResolvedValue({ sessions: own.slice(0, 1), total: 1, page: 0, size: 20 }),
      session: vi.fn().mockResolvedValue(mockSessionDetail),
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);

    await screen.findByText('Page 1 of 1');
    expect(screen.getAllByRole('button', { name: 'View' })).toHaveLength(1);
  });

  it('pages forward when the total exceeds one page', async () => {
    const sessions = vi.fn().mockResolvedValue(listPage({ total: 45 }));
    vi.mocked(api).mockReturnValue({
      sessions,
      session: vi.fn().mockResolvedValue(mockSessionDetail),
    } as unknown as ReturnType<typeof api>);

    render(<SessionsPage />);
    await waitFor(() => {
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    });

    expect(screen.getByRole('button', { name: '← Previous' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Next →' }));

    await waitFor(() => {
      expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
    });
    expect(sessions).toHaveBeenLastCalledWith(1);
  });

  it('disables paging when a single page holds every session', async () => {
    render(<SessionsPage />);

    await waitFor(() => {
      expect(screen.getByText('Page 1 of 1')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: '← Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next →' })).toBeDisabled();
  });
});
