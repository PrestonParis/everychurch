import { HttpErrorResponse } from '@angular/common/http';
import { faithError } from './faith.service';

function errorOf(status: number, error?: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, error });
}

describe('faithError', () => {
  it('maps 401 the same regardless of context', () => {
    expect(faithError(errorOf(401), 'beliefs')).toBe('Please sign in to continue.');
    expect(faithError(errorOf(401), 'quiz')).toBe('Please sign in to continue.');
  });

  it('reports Manage Beliefs only for the beliefs context (its default)', () => {
    expect(faithError(errorOf(403))).toBe('You need Manage Beliefs permission for this church.');
    expect(faithError(errorOf(403), 'beliefs')).toBe('You need Manage Beliefs permission for this church.');
  });

  it('does not mention Manage Beliefs for the quiz context — the reported bug: a user viewing their own saved quiz results should never see a per-church permission message', () => {
    expect(faithError(errorOf(403), 'quiz')).not.toContain('Manage Beliefs');
  });

  it('reports Platform Admin access for the admin context, not Manage Beliefs', () => {
    const message = faithError(errorOf(403), 'admin');
    expect(message).toContain('Platform Admin');
    expect(message).not.toContain('Manage Beliefs');
  });

  it('maps 409 to a stale-draft message regardless of context', () => {
    expect(faithError(errorOf(409), 'quiz')).toBe('This profile changed while you were editing. Reload before publishing.');
  });

  it('surfaces the server-provided message on a 400', () => {
    expect(faithError(errorOf(400, { error: 'That option is not valid for this question.' }))).toBe(
      'That option is not valid for this question.'
    );
  });

  it('falls back to a generic message for an unmapped status', () => {
    expect(faithError(errorOf(500))).toBe('We could not complete that request. Please try again.');
  });
});
