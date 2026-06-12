'use client';

import type { ReactNode } from 'react';

export function DataLoadState({
  loading,
  error,
  onRetry,
  children,
}: {
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  children: ReactNode;
}) {
  if (loading && !children) return <p>Loading...</p>;

  if (error) {
    return (
      <div style={styles.errorBox}>
        <p style={styles.errorText}>{error}</p>
        <button type="button" onClick={onRetry} style={styles.retryBtn}>
          Retry
        </button>
      </div>
    );
  }

  return children;
}

const styles: Record<string, React.CSSProperties> = {
  errorBox: {
    padding: '1.5rem',
    background: 'var(--bg-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 8,
  },
  errorText: {
    color: 'var(--red)',
    marginBottom: '1rem',
  },
  retryBtn: {
    padding: '0.5rem 1rem',
    background: 'var(--accent)',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    cursor: 'pointer',
  },
};
