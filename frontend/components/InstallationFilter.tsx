'use client';

import type { InstallationOption } from '@/lib/api';

/** Picks one installation, or all of them; hidden when the user can read only one. */
export function InstallationFilter({
  installations,
  value,
  onChange,
}: {
  installations: InstallationOption[];
  value: number | null;
  onChange: (next: number | null) => void;
}) {
  if (installations.length < 2) return null;
  return (
    <label style={styles.label}>
      Installation{' '}
      <select
        aria-label="Installation"
        value={value == null ? '' : String(value)}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        style={styles.select}
      >
        <option value="">All installations</option>
        {installations.map((i) => (
          <option key={i.id} value={String(i.id)}>
            {i.account}
          </option>
        ))}
      </select>
    </label>
  );
}

const styles: Record<string, React.CSSProperties> = {
  label: { color: 'var(--text-muted)', fontSize: '0.9rem' },
  select: {
    marginLeft: '0.5rem',
    background: 'var(--bg-secondary)',
    color: 'var(--text)',
    border: '1px solid var(--border)',
    borderRadius: 6,
    padding: '0.3rem 0.5rem',
  },
};
