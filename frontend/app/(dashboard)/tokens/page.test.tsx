import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TokensPage from './page';
import { api } from '@/lib/api';
import { mockTokenData } from '@/lib/mock-data';

vi.mock('react-chartjs-2', () => ({
  Bar: () => <div data-testid="bar-chart" />,
}));

vi.mock('@/lib/api', () => ({
  api: vi.fn(),
}));

describe('TokensPage', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    vi.mocked(api).mockReturnValue({
      tokens: vi.fn().mockResolvedValue(mockTokenData),
    } as ReturnType<typeof api>);
  });

  it('renders token analytics from mocked API data', async () => {
    render(<TokensPage />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Token Analytics' })).toBeInTheDocument();
    });

    expect(screen.getByText('184,320')).toBeInTheDocument();
    expect(screen.getByText('deepseek-chat')).toBeInTheDocument();
    expect(screen.getByTestId('bar-chart')).toBeInTheDocument();
  });

  it('shows an error and retry when the API fails', async () => {
    let calls = 0;
    const tokens = vi.fn().mockImplementation(() => {
      calls += 1;
      if (calls === 1) {
        return Promise.reject(new Error('API error: 503'));
      }
      return Promise.resolve(mockTokenData);
    });
    vi.mocked(api).mockReturnValue({ tokens } as ReturnType<typeof api>);

    render(<TokensPage />);

    await waitFor(() => {
      expect(screen.getByText('API error: 503')).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByTestId('bar-chart')).toBeInTheDocument();
    });
    expect(tokens.mock.calls.length).toBeGreaterThanOrEqual(2);
  });
});
