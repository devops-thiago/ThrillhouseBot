import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CostsPage from './page';
import { api } from '@/lib/api';
import { mockCostData } from '@/lib/mock-data';

vi.mock('react-chartjs-2', () => ({
  Bar: () => <div data-testid="bar-chart" />,
  Pie: () => <div data-testid="pie-chart" />,
}));

vi.mock('@/lib/api', () => ({
  api: vi.fn(),
}));

describe('CostsPage', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    vi.mocked(api).mockReturnValue({
      costs: vi.fn().mockResolvedValue(mockCostData),
    } as ReturnType<typeof api>);
  });

  it('renders cost analytics from mocked API data', async () => {
    render(<CostsPage />);

    expect(screen.getByText('Loading...')).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Cost Analytics' })).toBeInTheDocument();
    });

    expect(screen.getByText('$0.084512')).toBeInTheDocument();
    expect(screen.getByTestId('bar-chart')).toBeInTheDocument();
    expect(screen.getByTestId('pie-chart')).toBeInTheDocument();
  });

  it('shows an error and retry when the API fails', async () => {
    let calls = 0;
    const costs = vi.fn().mockImplementation(() => {
      calls += 1;
      if (calls === 1) {
        return Promise.reject(new Error('API error: 500'));
      }
      return Promise.resolve(mockCostData);
    });
    vi.mocked(api).mockReturnValue({ costs } as ReturnType<typeof api>);

    render(<CostsPage />);

    await waitFor(() => {
      expect(screen.getByText('API error: 500')).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByTestId('bar-chart')).toBeInTheDocument();
    });
    expect(costs.mock.calls.length).toBeGreaterThanOrEqual(2);
  });
});
