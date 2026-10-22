import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useInstallationFilter } from './useInstallationFilter';
import { api } from '@/lib/api';

vi.mock('@/lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api')>()),
  api: vi.fn(),
}));

const acme = { id: 7, account: 'acme', repositories: 2, installationWide: true };

describe('useInstallationFilter', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/');
  });

  it('starts from the installation in the URL and lists the readable ones', async () => {
    window.history.replaceState(null, '', '/?installation=7');
    vi.mocked(api).mockReturnValue({
      installations: vi.fn().mockResolvedValue([acme]),
    } as unknown as ReturnType<typeof api>);

    const { result } = renderHook(() => useInstallationFilter());

    expect(result.current.installation).toBe(7);
    await waitFor(() => expect(result.current.installations).toEqual([acme]));
  });

  it('keeps the choice in the URL and ignores a value that is not an id', async () => {
    window.history.replaceState(null, '', '/?installation=abc&id=3');
    vi.mocked(api).mockReturnValue({
      installations: vi.fn().mockRejectedValue(new Error('down')),
    } as unknown as ReturnType<typeof api>);

    const { result } = renderHook(() => useInstallationFilter());
    expect(result.current.installation).toBeNull();

    act(() => result.current.setInstallation(7));
    expect(window.location.search).toBe('?installation=7&id=3');
    act(() => result.current.setInstallation(null));
    expect(window.location.search).toBe('?id=3');
    expect(result.current.installations).toEqual([]);
  });
});
