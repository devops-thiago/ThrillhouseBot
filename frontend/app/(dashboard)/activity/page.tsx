'use client';

import { useCallback, useEffect, useMemo, useReducer, useState } from 'react';
import { useWebSocket, SessionEvent } from '@/hooks/useWebSocket';
import {
  PHASES,
  applyActivityEvent,
  formatCost,
  orderedReviews,
  phaseState,
  statusLabel,
  type ActivityState,
} from '@/lib/live-activity';

function selectedFromUrl(): number | null {
  if (typeof window === 'undefined') {
    return null;
  }
  const id = Number(new URLSearchParams(window.location.search).get('id'));
  return Number.isInteger(id) && id > 0 ? id : null;
}

export default function ActivityPage() {
  const [reviews, dispatch] = useReducer(applyActivityEvent, new Map<number, ActivityState>());
  const onEvent = useCallback((event: SessionEvent) => dispatch(event), []);
  const { connected } = useWebSocket(onEvent);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  useEffect(() => {
    setSelectedId(selectedFromUrl());
  }, []);

  const ordered = useMemo(() => orderedReviews(reviews), [reviews]);
  const selected =
    (selectedId !== null ? reviews.get(selectedId) : undefined) ?? ordered[0] ?? null;

  const select = (id: number) => {
    setSelectedId(id);
    const url = new URL(window.location.href);
    url.searchParams.set('id', String(id));
    window.history.replaceState(null, '', url);
  };

  return (
    <div>
      <h1 style={{ marginBottom: '1.5rem' }}>
        Live Activity {connected ? '🟢' : '🔴'}
      </h1>
      {ordered.length === 0 ? (
        <p style={{ color: 'var(--text-muted)' }}>
          No review is running. Reviews show here from the moment one starts until it ends.
        </p>
      ) : (
        <div style={styles.layout}>
          <nav aria-label="Reviews" style={styles.list}>
            {ordered.map((review) => (
              <button
                key={review.sessionId}
                type="button"
                onClick={() => select(review.sessionId)}
                aria-current={review.sessionId === selected?.sessionId ? 'true' : undefined}
                style={{
                  ...styles.listItem,
                  ...(review.sessionId === selected?.sessionId ? styles.listItemActive : {}),
                }}
              >
                <span style={{ fontWeight: 600 }}>
                  {review.repository}#{review.prNumber}
                </span>
                <span style={{ fontSize: '0.8rem', color: statusColor(review) }}>
                  {statusLabel(review)}
                </span>
              </button>
            ))}
          </nav>
          {selected && <ReviewActivity review={selected} />}
        </div>
      )}
    </div>
  );
}

function ReviewActivity({ review }: { review: ActivityState }) {
  const running = review.status === 'running' || review.status === 'retrying';
  return (
    <section style={styles.detail} aria-label={`${review.repository}#${review.prNumber}`}>
      <div>
        <h2 style={{ margin: 0 }}>
          {review.repository}#{review.prNumber}
        </h2>
        <span style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>{review.prTitle}</span>
      </div>

      <ol style={styles.phases} aria-label="Steps">
        {PHASES.map((phase) => {
          const state = phaseState(review, phase.id);
          return (
            <li key={phase.id} data-state={state} style={{ ...styles.phase, ...phaseStyles[state] }}>
              {state === 'done' ? '✓ ' : state === 'current' ? '● ' : ''}
              {phase.label}
              {phase.id === 'review' && review.batchCount ? (
                <span style={{ color: 'var(--text-muted)' }}>
                  {' '}
                  (batch {review.batchIndex}/{review.batchCount})
                </span>
              ) : null}
            </li>
          );
        })}
      </ol>

      <div style={styles.stats}>
        <Stat label="Status" value={statusLabel(review)} color={statusColor(review)} />
        <Stat label="Tokens" value={(review.inputTokens + review.outputTokens).toLocaleString()} />
        <Stat label="Cost so far" value={formatCost(review)} />
        {review.findings && (
          <Stat
            label="Findings"
            value={`${review.findings.critical}C / ${review.findings.high}H / ${review.findings.medium}M / ${review.findings.low}L`}
          />
        )}
      </div>
      {review.reason && <p style={{ color: 'var(--red)', margin: 0 }}>{review.reason}</p>}

      <StreamPanel
        title="Reasoning"
        text={review.reasoningTail}
        chars={review.reasoningChars}
        live={running}
        empty="No reasoning received. It shows when DASHBOARD_REASONING_STREAM_ENABLED is on and the provider sends it."
      />
      <StreamPanel
        title="Output"
        text={review.outputTail}
        chars={review.outputChars}
        live={running}
        empty={
          review.batchCount
            ? 'A review in batches does not stream its output; the batch count above shows its progress.'
            : 'No output yet.'
        }
      />
    </section>
  );
}

function StreamPanel({
  title,
  text,
  chars,
  live,
  empty,
}: {
  title: string;
  text: string;
  chars: number;
  live: boolean;
  empty: string;
}) {
  return (
    <div>
      <h3 style={styles.panelTitle}>
        {title}
        {chars > 0 && (
          <span style={{ fontWeight: 400, color: 'var(--text-muted)', fontSize: '0.8rem' }}>
            {' '}
            · {chars.toLocaleString()} characters
          </span>
        )}
      </h3>
      {text ? (
        <pre style={styles.streamBox}>
          {text}
          {live ? '\n▍' : ''}
        </pre>
      ) : (
        <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', margin: 0 }}>{empty}</p>
      )}
    </div>
  );
}

function Stat({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div style={styles.stat}>
      <div style={styles.statLabel}>{label}</div>
      <div style={{ fontWeight: 600, color }}>{value}</div>
    </div>
  );
}

function statusColor(review: ActivityState): string {
  switch (review.status) {
    case 'completed':
      return 'var(--green)';
    case 'failed':
      return 'var(--red)';
    case 'retrying':
      return 'var(--yellow)';
    default:
      return 'var(--accent)';
  }
}

const phaseStyles: Record<'done' | 'current' | 'pending', React.CSSProperties> = {
  done: { color: 'var(--green)', borderColor: 'var(--green)' },
  current: { color: 'var(--accent)', borderColor: 'var(--accent)', fontWeight: 600 },
  pending: { color: 'var(--text-muted)' },
};

const styles: Record<string, React.CSSProperties> = {
  layout: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '1rem',
    alignItems: 'flex-start',
  },
  list: { flex: '1 1 240px', display: 'flex', flexDirection: 'column', gap: '0.5rem' },
  listItem: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'flex-start',
    gap: '0.25rem',
    padding: '0.75rem 1rem',
    background: 'var(--bg-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 6,
    color: 'var(--text)',
    cursor: 'pointer',
    textAlign: 'left',
  },
  listItemActive: { borderColor: 'var(--accent)' },
  detail: {
    flex: '3 1 420px',
    minWidth: 0,
    display: 'flex',
    flexDirection: 'column',
    gap: '1rem',
    padding: '1rem 1.25rem',
    background: 'var(--bg-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 8,
  },
  phases: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '0.5rem',
    listStyle: 'none',
    margin: 0,
    padding: 0,
  },
  phase: {
    padding: '0.35rem 0.75rem',
    border: '1px solid var(--border)',
    borderRadius: 999,
    fontSize: '0.85rem',
  },
  stats: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
    gap: '0.75rem',
  },
  stat: { padding: '0.5rem 0' },
  statLabel: {
    fontSize: '0.75rem',
    color: 'var(--text-muted)',
    textTransform: 'uppercase',
    marginBottom: '0.25rem',
  },
  panelTitle: { fontSize: '0.95rem', margin: '0 0 0.5rem' },
  streamBox: {
    margin: 0,
    padding: '0.75rem',
    borderRadius: 6,
    overflow: 'auto',
    maxHeight: 280,
    background: 'rgba(0,0,0,0.25)',
    fontSize: '0.8rem',
    whiteSpace: 'pre-wrap',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
};
