'use client';

import { useCallback, useEffect, useState } from 'react';

type UseDashboardFetchOptions = {
  /** Keep showing previous data while a refetch is in flight (e.g. live summary updates). */
  keepStaleWhileLoading?: boolean;
};

export function useDashboardFetch<T>(
  fetcher: () => Promise<T>,
  deps: readonly unknown[],
  options: UseDashboardFetchOptions = {},
) {
  const { keepStaleWhileLoading = false } = options;
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const retry = useCallback(() => setReloadKey((key) => key + 1), []);

  useEffect(() => {
    let cancelled = false;
    const hadData = keepStaleWhileLoading && data !== null;
    setLoading(true);
    setError(null);
    if (!keepStaleWhileLoading) {
      setData(null);
    }

    fetcher()
      .then((result) => {
        if (cancelled) return;
        setData(result);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const message = err instanceof Error ? err.message : 'Request failed';
        setLoading(false);
        if (keepStaleWhileLoading && hadData) {
          setError(message);
          return;
        }
        setData(null);
        setError(message);
      });

    return () => {
      cancelled = true;
    };
  }, [reloadKey, keepStaleWhileLoading, ...deps]);

  return { data, loading, error, retry };
}
