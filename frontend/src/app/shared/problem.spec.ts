import { HttpErrorResponse } from '@angular/common/http';
import { problemMessage } from './problem';

describe('problemMessage', () => {
  const error = (status: number, body: unknown = null) =>
    new HttpErrorResponse({ status, error: body });

  it("prefers the API's own explanation", () => {
    expect(problemMessage(error(409, { detail: 'You can create up to 3 workspaces.' }))).toBe(
      'You can create up to 3 workspaces.',
    );
  });

  it('explains an unreachable server in plain words instead of a status code', () => {
    for (const status of [502, 503, 504]) {
      expect(problemMessage(error(status))).toBe(
        'The server is not reachable right now. Please try again in a moment.',
      );
    }
    expect(problemMessage(error(0))).toContain('not reachable');
  });

  it('falls back to the status for anything else', () => {
    expect(problemMessage(error(418))).toBe('Request failed (418).');
    expect(problemMessage(new Error('x'))).toBe('Something went wrong.');
  });
});
