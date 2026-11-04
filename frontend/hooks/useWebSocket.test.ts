import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchSocketTicket, socketUrl, useWebSocket } from './useWebSocket';

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

    expect(await fetchSocketTicket()).toEqual({ ticket: 'abc' });
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/socket-ticket', {
      method: 'POST',
      credentials: 'include',
    });
  });

  it.each([401, 403])('stops reconnecting on a %i answer', async (status) => {
    vi.stubGlobal('fetch', respond(status, { error: 'denied' }));

    expect(await fetchSocketTicket()).toEqual({ ticket: null, retry: false });
  });

  it('retries on a 503, which the server sends when it could not reach GitHub', async () => {
    vi.stubGlobal('fetch', respond(503, { error: 'Could not check your access with GitHub.' }));

    expect(await fetchSocketTicket()).toEqual({ ticket: null, retry: true });
  });

  it.each([{}, { ticket: '' }, { ticket: 42 }])('stops reconnecting on a malformed body %j', async (body) => {
    vi.stubGlobal('fetch', respond(200, body));

    expect(await fetchSocketTicket()).toEqual({ ticket: null, retry: false });
  });

  it('stops reconnecting on an answer that is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 200,
        ok: true,
        json: () => Promise.reject(new SyntaxError('Unexpected token <')),
      }),
    );

    expect(await fetchSocketTicket()).toEqual({ ticket: null, retry: false });
  });

  it('retries when the request gets no answer', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

    expect(await fetchSocketTicket()).toEqual({ ticket: null, retry: true });
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

describe('useWebSocket', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  class FakeSocket {
    static OPEN = 1;
    static CONNECTING = 0;
    static opened: string[] = [];
    readyState = FakeSocket.CONNECTING;
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onclose: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(url: string) {
      FakeSocket.opened.push(url);
    }
    close() {}
  }

  it('tries for a ticket again after a 503 instead of giving up', async () => {
    vi.useFakeTimers();
    FakeSocket.opened = [];
    vi.stubGlobal('WebSocket', FakeSocket);
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ status: 503, ok: false, json: () => Promise.resolve({}) })
      .mockResolvedValueOnce({ status: 200, ok: true, json: () => Promise.resolve({ ticket: 't' }) });
    vi.stubGlobal('fetch', fetchMock);

    renderHook(() => useWebSocket());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(FakeSocket.opened).toEqual([]);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(FakeSocket.opened).toHaveLength(1);
    expect(FakeSocket.opened[0]).toContain('ticket=t');
  });

  it('stops after a 401 and asks no more', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('WebSocket', FakeSocket);
    const fetchMock = respond(401);
    vi.stubGlobal('fetch', fetchMock);

    renderHook(() => useWebSocket());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
