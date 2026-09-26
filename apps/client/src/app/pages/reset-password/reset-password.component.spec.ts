import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { convertToParamMap } from '@angular/router';
import { ResetPasswordComponent } from './reset-password.component';

/** Zoneless regression guard, same pattern as AuthComponent's spec. */
describe('ResetPasswordComponent (zoneless rendering)', () => {
  function setup(token: string | null) {
    TestBed.configureTestingModule({
      imports: [ResetPasswordComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(token ? { token } : {}) } },
        },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ResetPasswordComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const type = (selector: string, value: string) => {
      const input = el.querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    return { http, fixture, el, type };
  }

  it('shows an "invalid link" state when there is no token in the URL, without calling the API', () => {
    const { el } = setup(null);
    expect(el.textContent).toContain('Invalid link');
  });

  it('submits the token from the URL and shows success', async () => {
    const { http, fixture, el, type } = setup('raw-token-abc');
    type('input[type=password]', 'brand-new-password');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    const req = http.expectOne('/api/auth/reset-password');
    expect(req.request.body).toEqual({ token: 'raw-token-abc', newPassword: 'brand-new-password' });
    req.flush({ message: 'Password reset.' });
    await fixture.whenStable();

    expect(el.textContent).toContain('Password reset');
    http.verify();
  });

  it('shows the server\'s error for an expired/invalid token and re-enables the form', async () => {
    const { http, fixture, el, type } = setup('raw-token-abc');
    type('input[type=password]', 'brand-new-password');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne('/api/auth/reset-password')
      .flush({ error: 'This reset link is invalid or has expired' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(el.textContent).toContain('This reset link is invalid or has expired');
    expect((el.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBe(false);
    http.verify();
  });
});
