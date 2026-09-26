import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ChurchEvent, ChurchRef } from '../../models/event.model';
import { EventService } from '../../services/event.service';
import { AuthService } from '../../services/auth.service';
import { ChurchStaffService } from '../../services/church-staff.service';
import { ChurchStaffSummary } from '../../models/church-staff.model';

@Component({
  selector: 'app-event-detail',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './event-detail.component.html',
})
export class EventDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private eventService = inject(EventService);
  private churchStaffService = inject(ChurchStaffService);
  authService = inject(AuthService);

  // Signal-backed (zoneless app: a plain field set in an HTTP callback doesn't repaint).
  private readonly _event = signal<ChurchEvent | null>(null);
  get event(): ChurchEvent | null { return this._event(); }
  loading = signal(true);
  error = signal(false);
  deleting = signal(false);

  private myStaffChurches = signal<ChurchStaffSummary[] | null>(null);
  respondingChurchId = signal<number | null>(null);

  private get managedChurchIds(): Set<number> {
    const summaries = this.myStaffChurches();
    if (!summaries) return new Set();
    return new Set(summaries.filter((s) => s.permissions.includes('MANAGE_EVENTS')).map((s) => s.churchId));
  }

  // Edit/Delete only for someone who could actually manage at least one of THIS
  // event's CONFIRMED churches — not just any church anywhere (a staff member of
  // Church A has no business editing Church B's event), and specifically not a
  // church that's only PENDING (its staff haven't agreed to co-host yet, so they
  // shouldn't be able to edit/delete the event before even responding to the
  // invitation) — mirrors EventService.requireManagesAtLeastOne/confirmedChurches.
  get canManage(): boolean {
    if (this.authService.currentUser()?.role === 'PLATFORM_ADMIN') return true;
    const event = this.event;
    if (!event) return false;
    const confirmedIds = (event.churches ?? []).map((c) => c.id);
    return confirmedIds.some((id) => this.managedChurchIds.has(id));
  }

  // Pending co-host invitations the current viewer can actually respond to — their
  // own church's MANAGE_EVENTS, or Platform Admin. Never offer a respond control for
  // a church the viewer doesn't manage.
  get respondablePendingChurches(): ChurchRef[] {
    const event = this.event;
    if (!event) return [];
    const isAdmin = this.authService.currentUser()?.role === 'PLATFORM_ADMIN';
    return (event.pendingChurches ?? []).filter((c) => isAdmin || this.managedChurchIds.has(c.id));
  }

  respondToCoHost(churchId: number, accept: boolean) {
    if (!this.event?.id || this.respondingChurchId() !== null) return;
    this.respondingChurchId.set(churchId);
    const obs = accept ? this.eventService.acceptCoHost(this.event.id, churchId) : this.eventService.declineCoHost(this.event.id, churchId);
    obs.subscribe({
      next: (updated) => {
        this._event.set(updated);
        this.respondingChurchId.set(null);
      },
      error: () => this.respondingChurchId.set(null),
    });
  }

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.eventService.get(id).subscribe({
      next: (event) => {
        this._event.set(event);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });

    if (this.authService.isAuthenticated()) {
      this.churchStaffService.myStaffChurches().subscribe({
        next: (summaries) => this.myStaffChurches.set(summaries),
        error: () => this.myStaffChurches.set([]),
      });
    }
  }

  formatDate(iso: string): string {
    return new Date(iso).toLocaleString(undefined, {
      weekday: 'long',
      month: 'long',
      day: 'numeric',
      year: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }

  deleteEvent() {
    if (!this.event?.id) return;
    if (!confirm('Delete this event? This cannot be undone.')) return;

    this.deleting.set(true);
    this.eventService.delete(this.event.id).subscribe({
      next: () => this.router.navigate(['/events']),
      error: () => this.deleting.set(false),
    });
  }
}
