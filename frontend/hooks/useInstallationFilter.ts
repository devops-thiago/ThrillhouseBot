'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, InstallationOption } from '@/lib/api';

const PARAM = 'installation';

function fromUrl(): number | null {
  if (typeof window === 'undefined') return null;
  const value = new URLSearchParams(window.location.search).get(PARAM);
  return value != null && /^\d+$/.test(value) ? Number(value) : null;
}

/**
 * The installations the signed-in user can read and the one the page is filtered to, kept in the
 * `installation` query parameter so a filtered view can be linked. `null` shows every installation.
 */
export function useInstallationFilter() {
  const [installations, setInstallations] = useState<InstallationOption[]>([]);
  const [installation, setSelected] = useState<number | null>(fromUrl);

  useEffect(() => {
    let cancelled = false;
    api()
      .installations()
      .then((list) => {
        if (!cancelled) setInstallations(list);
      })
      .catch(() => {
        // The filter is optional: without the list every installation stays shown.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const setInstallation = useCallback((next: number | null) => {
    setSelected(next);
    const url = new URL(window.location.href);
    if (next == null) {
      url.searchParams.delete(PARAM);
    } else {
      url.searchParams.set(PARAM, String(next));
    }
    window.history.replaceState(null, '', url);
  }, []);

  return { installations, installation, setInstallation };
}
