'use client';

import { useState, useEffect } from 'react';
import { MOCK_MODE, mockUser } from '@/lib/mock-data';

interface User {
  login: string;
  avatarUrl: string;
  name: string;
}

async function fetchAuthUser(): Promise<{
  user: User | null;
  accessDenied: boolean;
}> {
  const response = await fetch('/api/auth/me', { credentials: 'include' });
  if (response.status === 403) {
    return { user: null, accessDenied: true };
  }
  if (!response.ok) {
    return { user: null, accessDenied: false };
  }
  return { user: await response.json(), accessDenied: false };
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(MOCK_MODE ? mockUser : null);
  const [accessDenied, setAccessDenied] = useState(false);
  const [loading, setLoading] = useState(!MOCK_MODE);

  useEffect(() => {
    if (MOCK_MODE) return;

    let cancelled = false;

    const refresh = (background = false) => {
      fetchAuthUser()
        .then(({ user: nextUser, accessDenied: denied }) => {
          if (cancelled) return;
          setAccessDenied(denied);
          setUser(nextUser);
        })
        .catch(() => {
          if (cancelled || background) return;
          setAccessDenied(false);
          setUser(null);
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    };

    refresh();

    const onFocus = () => refresh(true);
    window.addEventListener('focus', onFocus);
    return () => {
      cancelled = true;
      window.removeEventListener('focus', onFocus);
    };
  }, []);

  const login = () => {
    window.location.href = "/api/auth/login";
  };

  const logout = async () => {
    try {
      await fetch("/api/auth/logout", { method: "POST", credentials: "include" });
    } finally {
      setUser(null);
      setAccessDenied(false);
      window.location.href = "/dashboard/";
    }
  };

  return { user, loading, accessDenied, login, logout, isAuthenticated: !!user };
}
