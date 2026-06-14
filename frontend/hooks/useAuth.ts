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
  notConfigured: boolean;
}> {
  const response = await fetch('/api/auth/me', { credentials: 'include' });
  if (response.status === 403) {
    return { user: null, accessDenied: true, notConfigured: false };
  }
  if (response.status === 503) {
    return { user: null, accessDenied: false, notConfigured: true };
  }
  if (!response.ok) {
    return { user: null, accessDenied: false, notConfigured: false };
  }
  return { user: await response.json(), accessDenied: false, notConfigured: false };
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(MOCK_MODE ? mockUser : null);
  const [accessDenied, setAccessDenied] = useState(false);
  const [notConfigured, setNotConfigured] = useState(false);
  const [loading, setLoading] = useState(!MOCK_MODE);

  useEffect(() => {
    if (MOCK_MODE) return;

    let cancelled = false;

    const refresh = (background = false) => {
      fetchAuthUser()
        .then(({ user: nextUser, accessDenied: denied, notConfigured: misconfigured }) => {
          if (cancelled) return;
          setAccessDenied(denied);
          setNotConfigured(misconfigured);
          setUser(nextUser);
        })
        .catch(() => {
          if (cancelled || background) return;
          setAccessDenied(false);
          setNotConfigured(false);
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
      setNotConfigured(false);
      window.location.href = "/dashboard/";
    }
  };

  return {
    user,
    loading,
    accessDenied,
    notConfigured,
    login,
    logout,
    isAuthenticated: !!user,
  };
}
