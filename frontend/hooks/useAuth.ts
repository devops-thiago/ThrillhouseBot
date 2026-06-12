'use client';

import { useState, useEffect } from 'react';

interface User {
  login: string;
  avatarUrl: string;
  name: string;
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(null);
  const [accessDenied, setAccessDenied] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/auth/me', { credentials: 'include' })
      .then(async (response) => {
        if (response.status === 403) {
          setAccessDenied(true);
          setUser(null);
          return;
        }
        if (!response.ok) {
          setAccessDenied(false);
          setUser(null);
          return;
        }
        setAccessDenied(false);
        setUser(await response.json());
      })
      .catch(() => {
        setAccessDenied(false);
        setUser(null);
      })
      .finally(() => setLoading(false));
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
