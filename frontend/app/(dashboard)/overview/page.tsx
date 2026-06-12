'use client';

import { useEffect, useMemo, useState } from 'react';
import { api, SessionSummary } from '@/lib/api';
import { useWebSocket, SessionEvent } from '@/hooks/useWebSocket';

interface LiveReviewState {
  sessionId: number;
  repository: string;
  prNumber: number;
  prTitle: string;
  status: 'streaming' | 'retrying' | 'stream_failed' | 'completed' | 'failed';
  attempt: number;
  maxAttempts: number;
  tail: string;
  totalChars: number;
  reason?: string;
  updatedAt: string;
}

export default function OverviewPage() {
  const [summary, setSummary] = useState<SessionSummary | null>(null);
  const { events, connected } = useWebSocket();

  useEffect(() => {
    api().summary().then(setSummary).catch(console.error);
  }, []);

  useEffect(() => {
    const latest = events[0];
    if (
      !latest ||
      (latest.type !== 'review.started' &&
        latest.type !== 'review.completed' &&
        latest.type !== 'review.failed')
    ) {
      return;
    }
    api().summary().then(setSummary).catch(console.error);
  }, [events]);

  const liveReviews = useMemo(() => buildLiveReviews(events), [events]);

  const liveEvents = events
    .filter(
      (e) =>
        e.type === 'review.started' ||
        e.type === 'review.completed' ||
        e.type === 'review.failed',
    )
    .slice(0, 10);

  return (
    <div>
      <h1 style={{ marginBottom: '1.5rem' }}>Dashboard Overview</h1>

      <div style={styles.grid}>
        <Card label="Total Reviews (30d)" value={summary?.totalReviews ?? '—'} />
        <Card label="Completed" value={summary?.completedReviews ?? '—'} color="var(--green)" />
        <Card label="Failed" value={summary?.failedReviews ?? '—'} color="var(--red)" />
        <Card label="Total Cost" value={summary ? `$${summary.totalCost.toFixed(4)}` : '—'} color="var(--yellow)" />
        <Card label="Top Model" value={summary?.topModel ?? '—'} />
      </div>

      <h2 style={{ marginTop: '2rem', marginBottom: '1rem' }}>
        Live Model Output {connected ? '🟢' : '🔴'}
      </h2>
      {liveReviews.length === 0 && (
        <p style={{ color: 'var(--text-muted)' }}>
          No active reviews. Open a PR to watch the model stream its JSON response here.
        </p>
      )}
      <div style={styles.feed}>
        {liveReviews.map((review) => (
          <LiveReviewCard key={review.sessionId} review={review} />
        ))}
      </div>

      <h2 style={{ marginTop: '2rem', marginBottom: '1rem' }}>Recent Activity</h2>
      {liveEvents.length === 0 && (
        <p style={{ color: 'var(--text-muted)' }}>No recent reviews. Push to a PR to see activity.</p>
      )}
      <div style={styles.feed}>
        {liveEvents.map((e) => (
          <EventCard key={`${e.sessionId}-${e.type}-${e.timestamp}`} event={e} />
        ))}
      </div>
    </div>
  );
}

function buildLiveReviews(events: SessionEvent[]): LiveReviewState[] {
  const bySession = new Map<number, LiveReviewState>();

  for (const event of [...events].reverse()) {
    const data = event.data ?? {};
    const attempt = Number(data.attempt ?? 1);
    const maxAttempts = Number(data.maxAttempts ?? 5);

    if (event.type === 'review.started') {
      bySession.set(event.sessionId, {
        sessionId: event.sessionId,
        repository: event.repository,
        prNumber: event.prNumber,
        prTitle: event.prTitle,
        status: 'streaming',
        attempt: 1,
        maxAttempts,
        tail: '',
        totalChars: 0,
        updatedAt: event.timestamp,
      });
      continue;
    }

    const current = bySession.get(event.sessionId) ?? createLiveReviewFromEvent(event);
    if (!bySession.has(event.sessionId)) {
      bySession.set(event.sessionId, current);
    }

    if (event.type === 'review.stream') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'streaming',
        attempt,
        maxAttempts,
        tail: String(data.tail ?? current.tail),
        totalChars: Number(data.totalChars ?? current.totalChars),
        updatedAt: event.timestamp,
      });
      continue;
    }

    if (event.type === 'review.retry') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'retrying',
        attempt,
        maxAttempts,
        reason: String(data.reason ?? ''),
        tail: '',
        totalChars: 0,
        updatedAt: event.timestamp,
      });
      continue;
    }

    if (event.type === 'review.stream.failed') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'stream_failed',
        attempt,
        maxAttempts,
        reason: String(data.reason ?? ''),
        updatedAt: event.timestamp,
      });
      continue;
    }

    if (event.type === 'review.completed') {
      bySession.delete(event.sessionId);
      continue;
    }

    if (event.type === 'review.failed') {
      bySession.set(event.sessionId, {
        ...current,
        status: 'failed',
        reason: String(data.errorType ?? 'Review failed'),
        updatedAt: event.timestamp,
      });
    }
  }

  return [...bySession.values()]
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .slice(0, 5);
}

function createLiveReviewFromEvent(event: SessionEvent): LiveReviewState {
  const data = event.data ?? {};
  const attempt = Number(data.attempt ?? 1);
  const maxAttempts = Number(data.maxAttempts ?? 5);

  if (event.type === 'review.stream') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'streaming',
      attempt,
      maxAttempts,
      tail: String(data.tail ?? ''),
      totalChars: Number(data.totalChars ?? 0),
      updatedAt: event.timestamp,
    };
  }

  if (event.type === 'review.retry') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'retrying',
      attempt,
      maxAttempts,
      tail: '',
      totalChars: 0,
      reason: String(data.reason ?? ''),
      updatedAt: event.timestamp,
    };
  }

  if (event.type === 'review.stream.failed') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'stream_failed',
      attempt,
      maxAttempts,
      tail: '',
      totalChars: 0,
      reason: String(data.reason ?? ''),
      updatedAt: event.timestamp,
    };
  }

  if (event.type === 'review.failed') {
    return {
      sessionId: event.sessionId,
      repository: event.repository,
      prNumber: event.prNumber,
      prTitle: event.prTitle,
      status: 'failed',
      attempt,
      maxAttempts,
      tail: '',
      totalChars: 0,
      reason: String(data.errorType ?? 'Review failed'),
      updatedAt: event.timestamp,
    };
  }

  return {
    sessionId: event.sessionId,
    repository: event.repository,
    prNumber: event.prNumber,
    prTitle: event.prTitle,
    status: 'streaming',
    attempt,
    maxAttempts,
    tail: '',
    totalChars: 0,
    updatedAt: event.timestamp,
  };
}

function Card({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <div style={styles.card}>
      <div style={styles.cardLabel}>{label}</div>
      <div style={{ ...styles.cardValue, color }}>{value}</div>
    </div>
  );
}

function LiveReviewCard({ review }: { review: LiveReviewState }) {
  const statusLabel =
    review.status === 'streaming'
      ? `Streaming attempt ${review.attempt}/${review.maxAttempts}`
      : review.status === 'retrying'
        ? `Retrying attempt ${review.attempt}/${review.maxAttempts}`
        : review.status === 'stream_failed'
          ? `Attempt ${review.attempt}/${review.maxAttempts} failed`
          : 'Review failed';

  return (
    <div style={styles.liveCard}>
      <div style={styles.liveHeader}>
        <span style={{ fontWeight: 600 }}>
          {review.repository}#{review.prNumber}
        </span>
        <span style={{ color: 'var(--accent)', fontSize: '0.85rem' }}>{statusLabel}</span>
      </div>
      <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>{review.prTitle}</span>
      {review.reason && (
        <span style={{ color: 'var(--red)', fontSize: '0.8rem' }}>{review.reason}</span>
      )}
      {review.tail ? (
        <pre style={styles.streamBox}>
          {review.tail}
          {review.status === 'streaming' ? '\n▍' : ''}
        </pre>
      ) : (
        <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', margin: 0 }}>
          Waiting for model tokens…
        </p>
      )}
      {review.totalChars > 0 && (
        <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
          {review.totalChars.toLocaleString()} characters received
        </span>
      )}
    </div>
  );
}

function EventCard({ event }: { event: SessionEvent }) {
  const isCompleted = event.type === 'review.completed';
  const isStarted = event.type === 'review.started';
  return (
    <div style={styles.eventCard}>
      <span
        style={{
          color: isCompleted ? 'var(--green)' : isStarted ? 'var(--accent)' : 'var(--red)',
          fontWeight: 600,
        }}
      >
        {isCompleted ? '✅' : isStarted ? '🔄' : '❌'} {event.repository}#{event.prNumber}
      </span>
      <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>{event.prTitle}</span>
      {isStarted && (
        <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Review in progress…</span>
      )}
      {isCompleted && event.data && (
        <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
          {event.data.critical ?? 0}C / {event.data.high ?? 0}H / {event.data.medium ?? 0}M /{' '}
          {event.data.low ?? 0}L — {event.data.totalTokens ?? 0} tokens, $
          {Number(event.data.cost ?? 0).toFixed(5)}
        </span>
      )}
      {!isCompleted && !isStarted && (
        <span style={{ fontSize: '0.8rem', color: 'var(--red)' }}>
          {event.data?.errorType ?? 'Error'}
        </span>
      )}
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  grid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
    gap: '1rem',
  },
  card: {
    background: 'var(--bg-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 8,
    padding: '1.25rem',
  },
  cardLabel: {
    fontSize: '0.8rem',
    color: 'var(--text-muted)',
    marginBottom: '0.5rem',
    textTransform: 'uppercase',
  },
  cardValue: { fontSize: '1.5rem', fontWeight: 700 },
  feed: { display: 'flex', flexDirection: 'column', gap: '0.5rem' },
  eventCard: {
    display: 'flex',
    flexDirection: 'column',
    gap: '0.25rem',
    padding: '0.75rem 1rem',
    background: 'var(--bg-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 6,
  },
  liveCard: {
    display: 'flex',
    flexDirection: 'column',
    gap: '0.5rem',
    padding: '0.9rem 1rem',
    background: 'var(--bg-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 8,
  },
  liveHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    gap: '1rem',
    alignItems: 'center',
  },
  streamBox: {
    margin: 0,
    padding: '0.75rem',
    borderRadius: 6,
    overflow: 'auto',
    maxHeight: 240,
    background: 'rgba(0,0,0,0.25)',
    fontSize: '0.8rem',
    whiteSpace: 'pre-wrap',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
};
