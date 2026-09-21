import { ChatMessage, mergeMessages } from './chat.model';

const msg = (id: number, body = `m${id}`): ChatMessage => ({
  id,
  churchId: 1,
  senderDisplayName: 'alice',
  body,
  sentAt: '2026-01-01T00:00:00Z',
});

describe('mergeMessages', () => {
  it('keeps messages in id order regardless of arrival order', () => {
    const merged = mergeMessages([msg(3), msg(1)], [msg(2)]);
    expect(merged.map((m) => m.id)).toEqual([1, 2, 3]);
  });

  it('shows a message once when it arrives both live and in a history reload', () => {
    const live = [msg(5)];
    const reloaded = [msg(4), msg(5), msg(6)];
    expect(mergeMessages(live, reloaded).map((m) => m.id)).toEqual([4, 5, 6]);
  });

  it('prefers the incoming copy of a duplicate id', () => {
    const merged = mergeMessages([msg(1, 'old')], [msg(1, 'new')]);
    expect(merged).toHaveLength(1);
    expect(merged[0].body).toBe('new');
  });

  it('does not mutate its inputs', () => {
    const existing = [msg(2)];
    const incoming = [msg(1)];
    mergeMessages(existing, incoming);
    expect(existing.map((m) => m.id)).toEqual([2]);
    expect(incoming.map((m) => m.id)).toEqual([1]);
  });

  it('handles empty sides', () => {
    expect(mergeMessages([], [])).toEqual([]);
    expect(mergeMessages([msg(1)], [])).toHaveLength(1);
    expect(mergeMessages([], [msg(1)])).toHaveLength(1);
  });
});
