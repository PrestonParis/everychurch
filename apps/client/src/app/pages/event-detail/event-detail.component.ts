import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ChurchEvent } from '../../models/event.model';
import { Church } from '../../models/church.model';
import { EventService } from '../../services/event.service';
import { ChurchService } from '../../services/church.service';
import { AuthService } from '../../services/auth.service';

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
  private churchService = inject(ChurchService);
  authService = inject(AuthService);

  event: ChurchEvent | null = null;
  churches = signal<Church[]>([]);
  loading = signal(true);
  error = signal(false);
  deleting = signal(false);

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.eventService.get(id).subscribe({
      next: (event) => {
        this.event = event;
        this.loadChurches(event.churchIds);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  private loadChurches(churchIds: number[]) {
    if (churchIds.length === 0) {
      this.loading.set(false);
      return;
    }
    forkJoin(churchIds.map((id) => this.churchService.getById(id).pipe(catchError(() => of(null))))).subscribe(
      (churches) => {
        this.churches.set(churches.filter((c): c is Church => c !== null));
        this.loading.set(false);
      }
    );
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
