import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { ChurchEvent } from '../../models/event.model';
import { CoHostInvitation } from '../../models/co-host-invitation.model';
import { EventService } from '../../services/event.service';
import { AuthService } from '../../services/auth.service';
import { ChurchStaffService, hasAnyChurchPermission } from '../../services/church-staff.service';

@Component({
  selector: 'app-event-list',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './event-list.component.html',
})
export class EventListComponent implements OnInit {
  private eventService = inject(EventService);
  private churchStaffService = inject(ChurchStaffService);
  authService = inject(AuthService);

  events = signal<ChurchEvent[]>([]);
  loading = signal(true);
  error = signal(false);

  // Whether to show "New Event" at all — mirrors the server's rule (EventService.
  // requireCanManage: PLATFORM_ADMIN, or MANAGE_EVENTS on at least one church) so a
  // plain Member with no church affiliation isn't offered a button that can only ever
  // end in a 403. Starts false and flips true once we know, rather than showing it
  // optimistically and yanking it away.
  canCreateEvents = signal(false);

  // Outstanding "will your church co-host this event?" asks against a church the
  // viewer manages — surfaced here, not just on the event's own page, since
  // otherwise an invitation is invisible unless someone happens to open that exact
  // event (found in review: co-host consent had no response path at all before).
  invitations = signal<CoHostInvitation[]>([]);
  respondingKey = signal<string | null>(null);

  ngOnInit() {
    this.eventService.list().subscribe({
      next: (events) => {
        this.events.set(
          [...events].sort((a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime())
        );
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });

    if (this.authService.currentUser()?.role === 'PLATFORM_ADMIN') {
      this.canCreateEvents.set(true);
    } else if (this.authService.isAuthenticated()) {
      this.churchStaffService.myStaffChurches().subscribe({
        next: (summaries) => this.canCreateEvents.set(hasAnyChurchPermission(summaries, 'MANAGE_EVENTS')),
        error: () => this.canCreateEvents.set(false),
      });
    }

    if (this.authService.isAuthenticated()) {
      this.eventService.myCoHostInvitations().subscribe({
        next: (invitations) => this.invitations.set(invitations),
        error: () => this.invitations.set([]),
      });
    }
  }

  respondToInvitation(invitation: CoHostInvitation, accept: boolean) {
    const key = `${invitation.eventId}-${invitation.churchId}`;
    if (this.respondingKey() !== null) return;
    this.respondingKey.set(key);

    const obs = accept
      ? this.eventService.acceptCoHost(invitation.eventId, invitation.churchId)
      : this.eventService.declineCoHost(invitation.eventId, invitation.churchId);

    obs.subscribe({
      next: () => {
        this.invitations.set(this.invitations().filter((i) => i !== invitation));
        this.respondingKey.set(null);
      },
      error: () => this.respondingKey.set(null),
    });
  }

  /** "X", "X & Y", or "X +N more" — church names, not "2 co-hosting churches". */
  hostSummary(event: ChurchEvent): string {
    const names = (event.churches ?? []).map((c) => c.name);
    if (names.length <= 2) return names.join(' & ');
    return `${names[0]} +${names.length - 1} more`;
  }

  formatDate(iso: string): string {
    return new Date(iso).toLocaleString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }
}
