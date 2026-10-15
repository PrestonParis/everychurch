import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { AuthComponent } from './auth.component';

/** Zoneless regression guard: a failed login must actually show its error (see church-profile spec for why these await whenStable). */
describe('AuthComponent (zoneless rendering)', () => {
  function setup(mode: 'login' | 'register') {
    TestBed.configureTestingModule({
      imports: [AuthComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { data: { mode } } } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AuthComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const type = (selector: string, value: string) => {
      const input = el.querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    return { http, fixture, el, type };
  }

  it('says the email or password is wrong when a login is rejected, and re-enables the form', async () => {
    const { http, fixture, el, type } = setup('login');
    type('input[type=email]', 'nobody@example.com');
    type('input[type=password]', 'wrong-password-123');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    // Let the render the submit event itself scheduled finish BEFORE the response arrives, as
    // in a real browser. Flushing synchronously would let that already-pending render pick up
    // the error by accident and hide the very bug this test exists to catch.
    await fixture.whenStable();
    http.expectOne('/api/auth/login').flush({ error: 'Invalid email or password' }, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(el.querySelector('[role=alert]')?.textContent).toContain('Incorrect email or password.');
    expect((el.querySelector('button[type=submit]') as HTMLButtonElement).textContent).toContain('Log in');
    http.verify();
  });

  it('says the server had a problem when it 500s with no message', async () => {
    const { http, fixture, el, type } = setup('register');
    type('input[type=email]', 'new@example.com');
    type('input[type=password]', 'password123');
    type('input[formcontrolname=displayName]', 'New Person');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable(); // see above: let the submit's own render finish first
    http.expectOne('/api/auth/register').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(el.textContent).toContain('Our server ran into a problem (error 500)');
    http.verify();
  });

  it('shows the status code for a non-JSON rejection instead of a bare "something went wrong"', async () => {
    // The real production failure: Spring's CORS filter answered with a plain-text 403.
    const { http, fixture, el, type } = setup('login');
    type('input[type=email]', 'someone@example.com');
    type('input[type=password]', 'whatever1');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne('/api/auth/login').flush('Invalid CORS request', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(el.textContent).toContain('Something went wrong (error 403)');
    http.verify();
  });

  it('allows logging in with a password shorter than the 8-character sign-up minimum', async () => {
    const { http, fixture, el, type } = setup('login');
    type('input[type=email]', 'old@example.com');
    type('input[type=password]', 'short');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne('/api/auth/login');
    http.verify();
  });
});
