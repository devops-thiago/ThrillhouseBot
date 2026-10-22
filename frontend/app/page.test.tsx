import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
    unavailable: false,
    login: vi.fn(),
    logout: vi.fn(),
    isAuthenticated: false,
    ...overrides,
  } as ReturnType<typeof useAuth>;
}

describe('LoginPage', () => {
  afterEach(() => {
    cleanup();
    replace.mockReset();
  });

  it('redirects a signed-in user to the overview after render', () => {
    vi.mocked(useAuth).mockReturnValue(authState({ user: mockUser, isAuthenticated: true }));

    const { container } = render(<LoginPage />);

    expect(container).toBeEmptyDOMElement();
    expect(replace).toHaveBeenCalledWith('/overview/');
  });

  it('shows the sign-in button and does not redirect when signed out', () => {
    vi.mocked(useAuth).mockReturnValue(authState({}));

    render(<LoginPage />);

    expect(screen.getByRole('button', { name: 'Sign in with GitHub' })).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it('does not redirect while the session is loading', () => {
    vi.mocked(useAuth).mockReturnValue(authState({ loading: true }));

    render(<LoginPage />);

    expect(screen.getByText('Loading...')).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it.each([
    ['access is denied', { accessDenied: true }, 'Access denied'],
    ['GitHub could not check access', { unavailable: true }, 'Access check unavailable'],
  ])('does not redirect when %s', (_label, overrides, heading) => {
    vi.mocked(useAuth).mockReturnValue(authState(overrides));

    render(<LoginPage />);

    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});
