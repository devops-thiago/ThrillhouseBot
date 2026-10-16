// @vitest-environment node
import { renderToString } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import LoginPage from './page';
import { useAuth } from '@/hooks/useAuth';
import { mockUser } from '@/lib/mock-data';

const replace = vi.fn();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
}));

vi.mock('@/hooks/useAuth', () => ({
  useAuth: vi.fn(),
}));

function authState(overrides: Partial<ReturnType<typeof useAuth>>) {
  return {
    user: null,
    loading: false,
    accessDenied: false,
    notConfigured: false,
    login: vi.fn(),
    logout: vi.fn(),
    isAuthenticated: false,
    ...overrides,
  } as ReturnType<typeof useAuth>;
}

describe('LoginPage server render', () => {
  it('renders a signed-in user without a window global', () => {
    expect(typeof window).toBe('undefined');
    vi.mocked(useAuth).mockReturnValue(authState({ user: mockUser, isAuthenticated: true }));

    expect(renderToString(<LoginPage />)).toBe('');
    expect(replace).not.toHaveBeenCalled();
  });

  it('renders the sign-in button without a window global', () => {
    vi.mocked(useAuth).mockReturnValue(authState({}));

    expect(renderToString(<LoginPage />)).toContain('Sign in with GitHub');
  });
});
