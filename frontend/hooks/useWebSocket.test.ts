import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchSocketTicket, socketUrl } from './useWebSocket';

function respond(status: number, body: unknown = {}) {
  return vi.fn().mockResolvedValue({
    status,
    ok: status >= 200 && status < 300,
    json: () => Promise.resolve(body),
  });
}

describe('fetchSocketTicket', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('posts for a ticket with the session cookie', async () => {
    const fetchMock = respond(200, { ticket: 'abc' });
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchSocketTicket()).toBe('abc');
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/socket-ticket', {
      method: 'POST',
      credentials: 'include',
    });
  });

  it.each([401, 403, 503])('returns null on a %i answer so the socket stops reconnecting', async (status) => {
    vi.stubGlobal('fetch', respond(status, { error: 'denied' }));

    expect(await fetchSocketTicket()).toBeNull();
  });

  it.each([{}, { ticket: '' }, { ticket: 42 }])('returns null for a malformed body %j', async (body) => {
    vi.stubGlobal('fetch', respond(200, body));

    expect(await fetchSocketTicket()).toBeNull();
  });

  it('returns null when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

    expect(await fetchSocketTicket()).toBeNull();
  });
});

describe('socketUrl', () => {
  it('carries the ticket in the query string over wss on https', () => {
    expect(socketUrl({ protocol: 'https:', host: 'bot.example' }, 'a+b/c')).toBe(
      'wss://bot.example/ws/dashboard?ticket=a%2Bb%2Fc',
    );
  });

  it('uses ws on plain http', () => {
    expect(socketUrl({ protocol: 'http:', host: 'localhost:8080' }, 't')).toBe(
      'ws://localhost:8080/ws/dashboard?ticket=t',
    );
  });
});
