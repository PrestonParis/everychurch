import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AdminClaimsComponent } from './admin-claims.component';

describe('AdminClaimsComponent', () => {
  const claim = (id: number, status = 'PENDING') => ({
    id, churchId: 10 + id, churchName: `Church ${id}`, requesterEmail: `user${id}@example.com`,
    status, notes: id === 1 ? 'I am the pastor' : null, createdAt: '2026-09-01T00:00:00Z',
  });

  function setup() {
    TestBed.configureTestingModule({
      imports: [AdminClaimsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AdminClaimsComponent);
    return { http, fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('lists pending claims with their notes and approves one, then reloads', async () => {
    const { http, fixture, el } = setup();
    fixture.detectChanges();

    http.expectOne((r) => r.url === '/api/admin/claims' && r.params.get('status') === 'PENDING').flush([claim(1), claim(2)]);
    await fixture.whenStable();
    expect(el.textContent).toContain('Church 1');
    expect(el.textContent).toContain('I am the pastor');
    expect(el.textContent).toContain('No note provided');

    const approve = Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.trim() === 'Approve')[0];
    approve.click();
    http.expectOne('/api/admin/claims/1/approve').flush(claim(1, 'APPROVED'));
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/admin/claims').flush([claim(2)]);
    await fixture.whenStable();

    expect(el.textContent).not.toContain('Church 1');
    http.verify();
  });

  it('explains itself when the request is refused (not a Platform Admin)', async () => {
    const { http, fixture, el } = setup();
    fixture.detectChanges();

    http.expectOne((r) => r.url === '/api/admin/claims').flush({ error: 'Forbidden' }, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(el.textContent).toContain('Forbidden');
    http.verify();
  });
});
