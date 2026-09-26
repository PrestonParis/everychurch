import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ForgotPasswordComponent } from './forgot-password.component';

/** Zoneless regression guard, same pattern as AuthComponent's spec. */
describe('ForgotPasswordComponent (zoneless rendering)', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [ForgotPasswordComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ForgotPasswordComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const type = (selector: string, value: string) => {
      const input = el.querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    return { http, fixture, el, type };
  }

  it('shows a generic "check your email" message on success, regardless of whether the account exists', async () => {
    const { http, fixture, el, type } = setup();
    type('input[type=email]', 'someone@example.com');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne('/api/auth/forgot-password').flush({ message: 'If that email has an account, a reset link has been sent.' });
    await fixture.whenStable();

    expect(el.textContent).toContain('Check your email');
    http.verify();
  });

  it('shows an error and re-enables the form on an actual server/network failure', async () => {
    const { http, fixture, el, type } = setup();
    type('input[type=email]', 'someone@example.com');
    await fixture.whenStable();

    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne('/api/auth/forgot-password').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(el.textContent).toContain('Something went wrong');
    expect((el.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBe(false);
    http.verify();
  });
});
