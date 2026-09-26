import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { ChurchStaffComponent } from './church-staff.component';

describe('ChurchStaffComponent', () => {
  const church = { id: 7, name: 'Hillside Community Church' };
  const owner = { id: 1, churchId: 7, userEmail: 'owner@example.com', isOwner: true, permissions: ['EDIT_PROFILE', 'MANAGE_SERMONS', 'MANAGE_EVENTS', 'MANAGE_STAFF', 'MANAGE_POSTS', 'MANAGE_BELIEFS'] };
  const staffer = { id: 2, churchId: 7, userEmail: 'events@example.com', isOwner: false, permissions: ['MANAGE_EVENTS'] };

  function setup() {
    TestBed.configureTestingModule({
      imports: [ChurchStaffComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ChurchStaffComponent);
    fixture.detectChanges();
    http.expectOne('/api/churches/7').flush(church);
    return { http, fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('lists the roster, marks the owner, and lets a permission be toggled for a non-owner', async () => {
    const { http, fixture, el } = setup();
    http.expectOne('/api/churches/7/staff').flush([owner, staffer]);
    await fixture.whenStable();

    expect(el.textContent).toContain('owner@example.com');
    expect(el.textContent).toContain('Owner');
    expect(el.textContent).toContain('events@example.com');

    const eventsCheckbox = Array.from(el.querySelectorAll('input[type=checkbox]')).find(
      (cb) => (cb as HTMLInputElement).checked
    ) as HTMLInputElement;
    expect(eventsCheckbox).toBeTruthy();

    eventsCheckbox.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    const req = http.expectOne('/api/churches/7/staff/2');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body.permissions).not.toContain('MANAGE_EVENTS');
    req.flush({ ...staffer, permissions: [] });
    await fixture.whenStable();

    http.verify();
  });

  it('adds a new staff member with the checked permissions', async () => {
    const { http, fixture, el } = setup();
    http.expectOne('/api/churches/7/staff').flush([owner]);
    await fixture.whenStable();

    const emailInput = el.querySelector('input[type=email]') as HTMLInputElement;
    emailInput.value = 'new@example.com';
    emailInput.dispatchEvent(new Event('input'));

    const sermonsCheckbox = Array.from(el.querySelectorAll('input[type=checkbox]'))[0] as HTMLInputElement;
    sermonsCheckbox.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    const addButton = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Add staff member'))!;
    addButton.click();

    const req = http.expectOne('/api/churches/7/staff');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.email).toBe('new@example.com');
    req.flush({ id: 3, churchId: 7, userEmail: 'new@example.com', isOwner: false, permissions: req.request.body.permissions });
    await fixture.whenStable();

    expect(el.textContent).toContain('new@example.com');
    http.verify();
  });

  it('shows the server error when the viewer is not allowed to manage staff', async () => {
    const { http, fixture, el } = setup();
    http.expectOne('/api/churches/7/staff').flush(
      { error: "You don't have permission to manage staff for this church" },
      { status: 403, statusText: 'Forbidden' }
    );
    await fixture.whenStable();

    expect(el.textContent).toContain("don't have permission");
    http.verify();
  });
});
