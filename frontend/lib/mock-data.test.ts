import { describe, expect, it } from 'vitest';
import { createMockApi } from './mock-data';

describe('createMockApi', () => {
  it('returns dashboard payloads matching API shapes', async () => {
    const mock = createMockApi();

    const costs = await mock.costs('week');
    expect(costs.period).toBe('week');
    expect(costs.byModel.length).toBeGreaterThan(0);

    const tokens = await mock.tokens('month');
    expect(tokens.totalTokens).toBeGreaterThan(0);

    const sessions = await mock.sessions(0);
    expect(sessions.sessions[0].repository).toContain('/');
  });
});
