import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Client, StompConfig } from '@stomp/stompjs';
import { AuthService } from '../../services/auth.service';
import { STOMP_CLIENT_FACTORY } from '../../services/chat.service';
import { ChurchProfileComponent } from './church-profile.component';

/**
 * Regression guard for the "profile page spins forever" bug. The app is zoneless, so a
 * component that assigns a plain field inside an HTTP callback is never re-rendered. These
 * tests deliberately never call fixture.detectChanges() after the response arrives — they
 * wait for Angular's own scheduled render (whenStable), which is exactly what a real
 * browser gets. Calling detectChanges() by hand would force a render and hide the bug.
 */
describe('ChurchProfileComponent (zoneless rendering)', () => {
  let http: HttpTestingController;

  const church = { id: 7, name: 'Hillside Community Church', denomination: 'Non-denominational', claimed: true, city: 'Chicago', state: 'IL' };

  function setup(auth: { authenticated: boolean; role?: string }) {
    TestBed.configureTestingModule({
      imports: [ChurchProfileComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
        // a member's chat panel mounts on this page — keep it off a real WebSocket
        {
          provide: STOMP_CLIENT_FACTORY,
          useValue: (config: StompConfig) =>
            ({ config, connected: false, activate: jest.fn(), deactivate: jest.fn().mockResolvedValue(undefined), subscribe: jest.fn(), publish: jest.fn() }) as unknown as Client,
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => auth.authenticated,
            getToken: () => 'jwt',
            currentUser: () => (auth.authenticated ? { email: 'a@b.c', role: auth.role ?? 'MEMBER' } : null),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ChurchProfileComponent);
    fixture.detectChanges(); // first render only — ngOnInit fires the requests
    return fixture;
  }

  /** Answers every request the page makes: the church itself, and empty lists for the rest. */
  function respond(status?: { member: boolean; staff: boolean; canChat: boolean }, churchBody: object | null = church) {
    for (const req of http.match(() => true)) {
      if (req.request.url === '/api/churches/7') {
        churchBody ? req.flush(churchBody) : req.flush({ error: 'not found' }, { status: 404, statusText: 'Not Found' });
      } else if (req.request.url === '/api/churches/7/members/me') {
        req.flush(status ?? { member: false, staff: false, canChat: false });
      } else {
        req.flush([]);
      }
    }
  }

  /**
   * Answer requests, let Angular render, repeat. The community and chat children only exist
   * (and only make their own requests) once the church has rendered, so one pass isn't enough.
   */
  async function settle(fixture: { whenStable: () => Promise<unknown> }, status?: { member: boolean; staff: boolean; canChat: boolean }, churchBody: object | null = church) {
    for (let round = 0; round < 3; round++) {
      respond(status, churchBody);
      await fixture.whenStable();
    }
  }

  const text = (fixture: { nativeElement: unknown }) => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const spinner = (fixture: { nativeElement: unknown }) => (fixture.nativeElement as HTMLElement).querySelector('.animate-spin');

  afterEach(() => http.verify());

  it('replaces the spinner with the church once it loads', async () => {
    const fixture = setup({ authenticated: false });
    expect(spinner(fixture)).not.toBeNull();

    await settle(fixture);

    expect(text(fixture)).toContain('Hillside Community Church');
    expect(spinner(fixture)).toBeNull();
  });

  it('shows "not found" instead of spinning forever when the church does not exist', async () => {
    const fixture = setup({ authenticated: false });

    await settle(fixture, undefined, null);

    expect(text(fixture)).toContain('Church not found');
    expect(spinner(fixture)).toBeNull();
  });

  it('hides the management controls from a logged-out visitor', async () => {
    const fixture = setup({ authenticated: false });
    await settle(fixture);

    expect(text(fixture)).not.toContain('+ Event');
  });

  it('hides the management controls from a plain member of a claimed church', async () => {
    const fixture = setup({ authenticated: true });
    await settle(fixture, { member: true, staff: false, canChat: true });

    expect(text(fixture)).toContain('Hillside Community Church');
    expect(text(fixture)).not.toContain('+ Event');
  });

  it('shows the management controls to that church\'s staff', async () => {
    const fixture = setup({ authenticated: true });
    await settle(fixture, { member: false, staff: true, canChat: true });

    expect(text(fixture)).toContain('+ Event');
  });

  it('shows them to a Platform Admin without needing to be staff', async () => {
    const fixture = setup({ authenticated: true, role: 'PLATFORM_ADMIN' });
    await settle(fixture, { member: false, staff: false, canChat: true });

    expect(text(fixture)).toContain('+ Event');
  });

  it('shows them to any logged-in user while the church is still unclaimed (the server allows it)', async () => {
    const fixture = setup({ authenticated: true });
    await settle(fixture, undefined, { ...church, claimed: false });

    expect(text(fixture)).toContain('+ Event');
    expect(text(fixture)).toContain('Is this your church?');
  });

  it('offers no claim card once the church has an owner', async () => {
    const fixture = setup({ authenticated: true });
    await settle(fixture);

    expect(text(fixture)).not.toContain('Is this your church?');
  });
});
