import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';

function respond(status: number, body: unknown = {}) {
  return vi.fn().mockResolvedValue({
    status,
    ok: status >= 200 && status < 300,
    json: () => Promise.resolve(body),
  });
}

describe('api', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('encodes the repository filter so it cannot add query parameters', async () => {
    const fetchMock = respond(200, { sessions: [], total: 0, page: 0, size: 20 });
    vi.stubGlobal('fetch', fetchMock);

    await api().sessions(0, 'acme/allowed&repository=acme/secret');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/dashboard/sessions?page=0&size=20&repository=acme%2Fallowed%26repository%3Dacme%2Fsecret',
      { credentials: 'include' },
    );
  });

  it('omits the repository filter when none is given', async () => {
    const fetchMock = respond(200, { sessions: [], total: 0, page: 0, size: 20 });
    vi.stubGlobal('fetch', fetchMock);

    await api().sessions(2);

    expect(fetchMock).toHaveBeenCalledWith('/api/dashboard/sessions?page=2&size=20', {
      credentials: 'include',
    });
  });

  it.each([403, 404, 500])('rejects a %i answer with an ApiError carrying the status', async (status) => {
    vi.stubGlobal('fetch', respond(status));

    const error = await api()
      .session(7)
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(status);
    expect((error as ApiError).message).toBe(`API error: ${status}`);
  });
});
