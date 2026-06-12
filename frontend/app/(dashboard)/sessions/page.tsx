'use client';

import { useEffect, useState } from 'react';
import { api, SessionDetail, SessionListItem } from '@/lib/api';

export default function SessionsPage() {
  const [sessions, setSessions] = useState<SessionListItem[]>([]);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const selectSession = (id: number | null) => {
    setSelectedId(id);
    const url = new URL(window.location.href);
    if (id == null) {
      url.searchParams.delete('id');
    } else {
      url.searchParams.set('id', String(id));
    }
    window.history.replaceState(null, '', url);
  };

  useEffect(() => {
    const fromUrl = new URLSearchParams(window.location.search).get('id');
    if (fromUrl != null && /^\d+$/.test(fromUrl)) {
      setSelectedId(Number(fromUrl));
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api()
      .sessions(page)
      .then((data) => {
        if (cancelled) return;
        setSessions(data.sessions);
        setTotal(data.total);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : 'Failed to load sessions');
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [page, reloadKey]);

  useEffect(() => {
    if (selectedId == null) {
      setDetail(null);
      return;
    }
    setDetail(null);
    setDetailLoading(true);
    let cancelled = false;
    api()
      .session(selectedId)
      .then((data) => {
        if (cancelled) return;
        setDetail(data);
        setDetailLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        setDetail(null);
        setDetailLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  const parsedAi = parseAiResponse(detail?.aiResponseJson);

  return (
    <div>
      <h1 style={{ marginBottom: '1.5rem' }}>Session History</h1>

      {loading && <p>Loading...</p>}

      {!loading && error && (
        <div style={styles.loadErrorBox}>
          <span style={{ color: 'var(--red)' }}>
            Could not load sessions: {error}
          </span>
          <button
            type="button"
            onClick={() => setReloadKey((k) => k + 1)}
            style={styles.retryBtn}
          >
            Retry
          </button>
        </div>
      )}

      {!loading && !error && (
        <>
          <div style={styles.chartCard}>
            <table style={styles.table}>
              <thead>
                <tr>
                  <th style={styles.th}>Repo</th>
                  <th style={styles.th}>PR</th>
                  <th style={styles.th}>Model</th>
                  <th style={styles.th}>Status</th>
                  <th style={styles.thRight}>Tokens</th>
                  <th style={styles.thRight}>Cost</th>
                  <th style={styles.th}>Findings</th>
                  <th style={styles.th}>Time</th>
                  <th style={styles.th}></th>
                </tr>
              </thead>
              <tbody>
                {sessions.map((s) => (
                  <tr
                    key={s.id}
                    style={{
                      background:
                        selectedId === s.id ? 'rgba(88,166,255,0.08)' : undefined,
                    }}
                  >
                    <td
                      style={{
                        ...styles.td,
                        maxWidth: 150,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {s.repository}
                    </td>
                    <td style={styles.td}>
                      <a
                        href={`https://github.com/${s.repository}/pull/${s.prNumber}`}
                        target="_blank"
                        rel="noopener"
                        onClick={(e) => e.stopPropagation()}
                      >
                        #{s.prNumber}
                      </a>
                    </td>
                    <td style={styles.td}>{s.model || '—'}</td>
                    <td
                      style={{
                        ...styles.td,
                        textAlign: 'center',
                        color: statusColor(s.status),
                      }}
                      title={s.status}
                    >
                      {statusEmoji(s.status)}
                    </td>
                    <td style={styles.tdRight}>{(s.inputTokens + s.outputTokens).toLocaleString()}</td>
                    <td style={{ ...styles.tdRight, color: 'var(--yellow)' }}>
                      ${s.cost.toFixed(6)}
                    </td>
                    <td style={{ ...styles.td, whiteSpace: 'nowrap' }}>
                      {s.criticalFindings}C / {s.highFindings}H / {s.mediumFindings}M /{' '}
                      {s.lowFindings}L
                    </td>
                    <td style={{ ...styles.td, fontSize: '0.8rem', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                      {new Date(s.timestamp).toLocaleString()}
                    </td>
                    <td style={styles.td}>
                      <button
                        type="button"
                        style={styles.linkButton}
                        onClick={() => selectSession(s.id)}
                      >
                        View
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={{ display: 'flex', justifyContent: 'center', gap: '1rem', marginTop: '1rem' }}>
            <button
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0}
              style={styles.button}
            >
              ← Previous
            </button>
            <span style={{ padding: '0.5rem', color: 'var(--text-muted)' }}>
              Page {page + 1} of {Math.max(1, Math.ceil(total / 20))}
            </span>
            <button
              onClick={() => setPage((p) => p + 1)}
              disabled={(page + 1) * 20 >= total}
              style={styles.button}
            >
              Next →
            </button>
          </div>
        </>
      )}

      {selectedId != null && (
        <div style={styles.overlay} onClick={() => selectSession(null)}>
          <div style={styles.panel} onClick={(e) => e.stopPropagation()}>
            <div style={styles.panelHeader}>
              <h2 style={{ margin: 0 }}>Session #{selectedId}</h2>
              <button type="button" style={styles.button} onClick={() => selectSession(null)}>
                Close
              </button>
            </div>

            {detailLoading && <p>Loading session details...</p>}

            {!detailLoading && !detail && (
              <p style={{ color: 'var(--text-muted)' }}>Could not load session details.</p>
            )}

            {!detailLoading && detail && (
              <>
                <div style={styles.metaGrid}>
                  <Meta label="Repository" value={detail.repository} />
                  <Meta
                    label="Pull request"
                    value={
                      <a
                        href={`https://github.com/${detail.repository}/pull/${detail.prNumber}`}
                        target="_blank"
                        rel="noopener"
                      >
                        #{detail.prNumber} — {detail.prTitle}
                      </a>
                    }
                  />
                  <Meta label="Commit" value={detail.commitSha} />
                  <Meta label="Status" value={detail.status} />
                  <Meta label="Model" value={detail.model || '—'} />
                  <Meta
                    label="Tokens"
                    value={`${detail.inputTokens.toLocaleString()} in / ${detail.outputTokens.toLocaleString()} out`}
                  />
                  <Meta label="Cost" value={`$${detail.cost.toFixed(6)}`} />
                  <Meta label="Duration" value={`${detail.durationMs} ms`} />
                  <Meta
                    label="Findings"
                    value={`${detail.criticalFindings}C / ${detail.highFindings}H / ${detail.mediumFindings}M / ${detail.lowFindings}L`}
                  />
                  <Meta label="Started" value={new Date(detail.timestamp).toLocaleString()} />
                </div>

                {detail.errorMessage && (
                  <div style={styles.errorBox}>
                    <strong>Error</strong>
                    <p style={{ margin: '0.5rem 0 0' }}>{detail.errorMessage}</p>
                  </div>
                )}

                <h3 style={{ marginTop: '1.5rem' }}>Model output</h3>
                {!detail.aiResponseJson && (
                  <p style={{ color: 'var(--text-muted)' }}>
                    No model output was stored for this session. Only reviews after this update
                    persist the AI response.
                  </p>
                )}

                {parsedAi?.summary && (
                  <p style={{ color: 'var(--text-muted)', marginBottom: '1rem' }}>
                    {parsedAi.summary.overall_assessment ||
                      `${parsedAi.summary.total_findings ?? parsedAi.findings?.length ?? 0} findings`}
                  </p>
                )}

                {parsedAi?.findings && parsedAi.findings.length > 0 ? (
                  <div style={styles.findingsList}>
                    {parsedAi.findings.map((finding, index) => (
                      <div key={`${finding.file}-${finding.line}-${index}`} style={styles.findingCard}>
                        <div style={styles.findingHeader}>
                          <span style={{ textTransform: 'uppercase', fontWeight: 700 }}>
                            {finding.risk}
                          </span>
                          <span style={{ color: 'var(--text-muted)' }}>
                            {finding.file}:{finding.line}
                          </span>
                        </div>
                        <strong>{finding.title}</strong>
                        <p style={{ margin: '0.5rem 0' }}>{finding.description}</p>
                        {finding.suggestion_old && finding.suggestion_new && (
                          <pre style={styles.codeBlock}>
                            {`- ${finding.suggestion_old}\n+ ${finding.suggestion_new}`}
                          </pre>
                        )}
                      </div>
                    ))}
                  </div>
                ) : (
                  detail.aiResponseJson && (
                    <p style={{ color: 'var(--text-muted)' }}>The model returned no findings.</p>
                  )
                )}

                {detail.aiResponseJson && (
                  <details style={{ marginTop: '1rem' }}>
                    <summary style={{ cursor: 'pointer' }}>Raw JSON</summary>
                    <pre style={styles.codeBlock}>{formatJson(detail.aiResponseJson)}</pre>
                  </details>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function statusEmoji(status: string) {
  if (status === 'completed') return '✅';
  if (status === 'failed') return '❌';
  return '⏳';
}

function statusColor(status: string) {
  if (status === 'completed') return 'var(--green)';
  if (status === 'failed') return 'var(--red)';
  return 'var(--yellow)';
}

function Meta({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div style={styles.metaLabel}>{label}</div>
      <div>{value}</div>
    </div>
  );
}

function parseAiResponse(raw?: string | null) {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as {
      findings?: Array<{
        risk: string;
        file: string;
        line: number;
        title: string;
        description: string;
        suggestion_old?: string;
        suggestion_new?: string;
      }>;
      summary?: {
        total_findings?: number;
        overall_assessment?: string;
      };
    };
  } catch {
    return null;
  }
}

function formatJson(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

const styles: Record<string, React.CSSProperties> = {
  chartCard: {
    background: 'var(--bg-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 8,
    padding: '1rem',
    overflow: 'auto',
  },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' },
  th: {
    textAlign: 'left',
    padding: '0.55rem 0.75rem',
    borderBottom: '1px solid var(--border)',
    fontSize: '0.72rem',
    fontWeight: 600,
    color: 'var(--text-muted)',
    textTransform: 'uppercase',
    whiteSpace: 'nowrap',
  },
  thRight: {
    textAlign: 'right',
    padding: '0.55rem 0.75rem',
    borderBottom: '1px solid var(--border)',
    fontSize: '0.72rem',
    fontWeight: 600,
    color: 'var(--text-muted)',
    textTransform: 'uppercase',
    whiteSpace: 'nowrap',
  },
  td: {
    textAlign: 'left',
    padding: '0.55rem 0.75rem',
    borderBottom: '1px solid var(--border)',
    verticalAlign: 'middle',
  },
  tdRight: {
    textAlign: 'right',
    padding: '0.55rem 0.75rem',
    borderBottom: '1px solid var(--border)',
    verticalAlign: 'middle',
    whiteSpace: 'nowrap',
    fontVariantNumeric: 'tabular-nums',
  },
  button: {
    padding: '0.5rem 1rem',
    background: 'var(--bg-secondary)',
    color: 'var(--text)',
    border: '1px solid var(--border)',
    borderRadius: 6,
    cursor: 'pointer',
  },
  linkButton: {
    padding: '0.25rem 0.6rem',
    background: 'transparent',
    color: 'var(--accent)',
    border: '1px solid var(--border)',
    borderRadius: 6,
    cursor: 'pointer',
  },
  overlay: {
    position: 'fixed',
    inset: 0,
    background: 'rgba(0,0,0,0.55)',
    display: 'flex',
    justifyContent: 'flex-end',
    zIndex: 200,
  },
  panel: {
    width: 'min(720px, 100%)',
    height: '100%',
    overflow: 'auto',
    background: 'var(--bg)',
    borderLeft: '1px solid var(--border)',
    padding: '1.25rem',
  },
  panelHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '1rem',
  },
  metaGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
    gap: '0.75rem 1rem',
  },
  metaLabel: {
    fontSize: '0.75rem',
    color: 'var(--text-muted)',
    textTransform: 'uppercase',
    marginBottom: '0.25rem',
  },
  errorBox: {
    marginTop: '1rem',
    padding: '0.75rem 1rem',
    borderRadius: 8,
    border: '1px solid var(--red)',
    background: 'rgba(248,81,73,0.08)',
  },
  loadErrorBox: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: '1rem',
    flexWrap: 'wrap',
    padding: '1rem 1.25rem',
    borderRadius: 8,
    border: '1px solid var(--red)',
    background: 'rgba(248,81,73,0.08)',
  },
  retryBtn: {
    padding: '0.5rem 1rem',
    background: 'var(--accent)',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    cursor: 'pointer',
  },
  findingsList: { display: 'flex', flexDirection: 'column', gap: '0.75rem' },
  findingCard: {
    padding: '0.75rem 1rem',
    borderRadius: 8,
    border: '1px solid var(--border)',
    background: 'var(--bg-secondary)',
  },
  findingHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    gap: '1rem',
    marginBottom: '0.35rem',
  },
  codeBlock: {
    marginTop: '0.5rem',
    padding: '0.75rem',
    borderRadius: 6,
    overflow: 'auto',
    background: 'rgba(0,0,0,0.25)',
    fontSize: '0.8rem',
    whiteSpace: 'pre-wrap',
  },
};
