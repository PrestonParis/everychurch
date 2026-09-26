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

  /** Answers every request the page makes: the church itself, and empty lists for the rest.
   *  permissions is this viewer's ChurchStaffDto.permissions for church 7 specifically
   *  (see /api/users/me/staff-churches) — undefined means "not staff anywhere". */
  function respond(
    status?: { member: boolean; staff: boolean; canChat: boolean },
    churchBody: object | null = church,
    permissions?: string[]
  ) {
    for (const req of http.match(() => true)) {
      if (req.request.url === '/api/churches/7') {
        churchBody ? req.flush(churchBody) : req.flush({ error: 'not found' }, { status: 404, statusText: 'Not Found' });
      } else if (req.request.url === '/api/churches/7/members/me') {
        req.flush(status ?? { member: false, staff: false, canChat: false });
      } else if (req.request.url === '/api/users/me/staff-churches') {
        req.flush(permissions ? [{ id: 1, churchId: 7, userEmail: 'a@b.c', isOwner: false, permissions }] : []);
      } else {
        req.flush([]);
      }
    }
  }

  /**
   * Answer requests, let Angular render, repeat. The community and chat children only exist
   * (and only make their own requests) once the church has rendered, so one pass isn't enough.
   */
  async function settle(
    fixture: { whenStable: () => Promise<unknown> },
    status?: { member: boolean; staff: boolean; canChat: boolean },
    churchBody: object | null = church,
    permissions?: string[]
  ) {
    for (let round = 0; round < 3; round++) {
      respond(status, churchBody, permissions);
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

  it('shows the management controls a staff member actually has permission for', async () => {
    const fixture = setup({ authenticated: true });
    await settle(fixture, { member: false, staff: true, canChat: true }, church, [
      'EDIT_PROFILE', 'MANAGE_SERMONS', 'MANAGE_EVENTS',
    ]);

    expect(text(fixture)).toContain('+ Event');
    expect(text(fixture)).toContain('Edit');
    expect(text(fixture)).toContain('Sermons');
  });

  it('only shows the specific control a limited-permission staff member actually has — not every control, per a plain "is staff" check', async () => {
    const fixture = setup({ authenticated: true });
    // appointed for events only — MemberService still reports them as "staff" (that
    // boolean just means "has some ChurchStaff row"), but they must not be offered
    // Edit/Photos/Sermons, which the server would reject.
    await settle(fixture, { member: false, staff: true, canChat: true }, church, ['MANAGE_EVENTS']);

    expect(text(fixture)).toContain('+ Event');
    expect(text(fixture)).not.toContain('Edit');
    expect(text(fixture)).not.toContain('Photos');
    expect(text(fixture)).not.toContain('Sermons');
  });

  it('shows them to a Platform Admin without needing to be staff', async () => {
    const fixture = setup({ authenticated: true, role: 'PLATFORM_ADMIN' });
    await settle(fixture, { member: false, staff: false, canChat: true });

    expect(text(fixture)).toContain('+ Event');
  });

  it('hides the management controls from a plain logged-in user for an unclaimed church (Platform Admin only, per docs/PROMPTS.md)', async () => {
    const fixture = setup({ authenticated: true });
    await settle(fixture, undefined, { ...church, claimed: false });

    expect(text(fixture)).not.toContain('+ Event');
    expect(text(fixture)).toContain('Is this your church?');
  });

  it('shows them to a Platform Admin for an unclaimed church', async () => {
    const fixture = setup({ authenticated: true, role: 'PLATFORM_ADMIN' });
    await settle(fixture, undefined, { ...church, claimed: false });

    expect(text(fixture)).toContain('+ Event');
  });

  it('offers no claim card once the church has an owner', async () => {
    const fixture = setup({ authenticated: true });
    await settle(fixture);

    expect(text(fixture)).not.toContain('Is this your church?');
  });
});
