import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ChurchCommunityComponent } from './church-community.component';

describe('ChurchCommunityComponent', () => {
  let http: HttpTestingController;

  function setup(authenticated: boolean) {
    TestBed.configureTestingModule({
      imports: [ChurchCommunityComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { isAuthenticated: () => authenticated, getToken: () => 'jwt', currentUser: () => null } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ChurchCommunityComponent);
    fixture.componentRef.setInput('churchId', 5);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http.verify());

  it('sends a logged-out visitor to log in and makes no membership requests', () => {
    const fixture = setup(false);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Log in');
    http.expectNone((r) => r.url.includes('/members'));
  });

  it('offers a non-member a Join button, then refreshes after joining', () => {
    const fixture = setup(true);
    http.expectOne('/api/churches/5/members/me').flush({ member: false, staff: false, canChat: false });
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const button = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Join this church'));
    expect(button).toBeTruthy();
    // a non-member must not be shown the private member list or the chat panel
    expect(el.querySelector('app-church-chat')).toBeNull();

    button!.click();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/churches/5/members').flush({ id: 1 });
    // joining re-reads the status so the UI reflects what the server now says
    http.expectOne('/api/churches/5/members/me').flush({ member: true, staff: false, canChat: true });
    http.expectOne('/api/churches/5/members').flush([]);
    // canChat now true, so the chat panel mounts and asks for history
    fixture.detectChanges();
    http.match((r) => r.url === '/api/churches/5/messages').forEach((r) => r.flush([]));
  });

  it('shows the server\'s refusal instead of silently failing when joining is rejected', () => {
    const fixture = setup(true);
    http.expectOne('/api/churches/5/members/me').flush({ member: false, staff: false, canChat: false });
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Join this church'))!.click();
    http.expectOne((r) => r.method === 'POST').flush({ error: "You're already a member of this church" }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el.textContent).toContain("You're already a member of this church");
  });
});
