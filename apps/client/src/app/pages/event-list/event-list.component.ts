import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { ChurchEvent } from '../../models/event.model';
import { EventService } from '../../services/event.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-event-list',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './event-list.component.html',
})
export class EventListComponent implements OnInit {
  private eventService = inject(EventService);
  authService = inject(AuthService);

  events = signal<ChurchEvent[]>([]);
  loading = signal(true);
  error = signal(false);

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
