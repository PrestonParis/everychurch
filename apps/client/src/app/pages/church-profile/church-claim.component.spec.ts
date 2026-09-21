import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ChurchClaimComponent } from './church-claim.component';

describe('ChurchClaimComponent', () => {
  let http: HttpTestingController;

  function setup(opts: { authenticated: boolean; claimed?: boolean }) {
    TestBed.configureTestingModule({
      imports: [ChurchClaimComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { isAuthenticated: () => opts.authenticated } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ChurchClaimComponent);
    fixture.componentRef.setInput('churchId', 9);
    fixture.componentRef.setInput('claimed', opts.claimed ?? false);
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: { nativeElement: unknown }) => f.nativeElement as HTMLElement;
  const button = (f: { nativeElement: unknown }, label: string) =>
    Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.includes(label));

  afterEach(() => http.verify());

  it('renders nothing once the church has an owner', () => {
    expect(el(setup({ authenticated: true, claimed: true })).textContent?.trim()).toBe('');
  });

  it('sends a logged-out visitor to log in rather than offering the button', () => {
    const fixture = setup({ authenticated: false });
    expect(el(fixture).textContent).toContain('Log in');
    expect(button(fixture, 'Claim this church')).toBeUndefined();
  });

  it('submits the request with the note and confirms it', async () => {
    const fixture = setup({ authenticated: true });
    button(fixture, 'Claim this church')!.click();
    await fixture.whenStable();

    const textarea = el(fixture).querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = 'I am the pastor';
    textarea.dispatchEvent(new Event('input'));
    button(fixture, 'Send request')!.click();
    await fixture.whenStable();

    const req = http.expectOne('/api/churches/9/claims');
    expect(req.request.body).toEqual({ notes: 'I am the pastor' });
    req.flush({ id: 1, status: 'PENDING' });
    await fixture.whenStable();

    expect(el(fixture).textContent).toContain('your request is with our team');
  });

  it('shows the server\'s reason when a claim is refused (e.g. one is already pending)', async () => {
    const fixture = setup({ authenticated: true });
    button(fixture, 'Claim this church')!.click();
    await fixture.whenStable();
    button(fixture, 'Send request')!.click();
    await fixture.whenStable();

    http.expectOne('/api/churches/9/claims').flush({ error: 'You already have a pending claim on this church' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(el(fixture).textContent).toContain('You already have a pending claim on this church');
    expect(button(fixture, 'Send request')).toBeTruthy(); // can retry or cancel
  });
});
