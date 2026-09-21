import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, forkJoin, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { Church } from '../../models/church.model';
import { ChurchEvent } from '../../models/event.model';
import { ChurchService } from '../../services/church.service';
import { EventService } from '../../services/event.service';

@Component({
  selector: 'app-event-form',
  standalone: true,
  imports: [RouterModule, FormsModule],
  templateUrl: './event-form.component.html',
})
export class EventFormComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private churchService = inject(ChurchService);
  private eventService = inject(EventService);

  isEdit = false;
  eventId: number | null = null;
  loading = signal(false);
  saving = signal(false);
  saveError = signal('');

  title = '';
  description = '';
  startsAt = ''; // <input type="datetime-local"> value
  endsAt = '';

  selectedChurches = signal<Church[]>([]);
  churchQuery = '';
  churchResults = signal<Church[]>([]);
  searching = signal(false);
  private churchQueryChanged = new Subject<string>();

  constructor() {
    this.churchQueryChanged
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((query) => {
          if (!query.trim()) return of({ content: [] });
          this.searching.set(true);
          return this.churchService.search({ query: query.trim(), size: 10 });
        })
      )
      .subscribe((result) => {
        const selectedIds = new Set(this.selectedChurches().map((c) => c.id));
        this.churchResults.set(result.content.filter((c) => !selectedIds.has(c.id)));
        this.searching.set(false);
      });
  }

  ngOnInit() {
    const idParam = this.route.snapshot.paramMap.get('id');
    const preselectChurchId = this.route.snapshot.queryParamMap.get('churchId');

    if (idParam) {
      this.isEdit = true;
      this.eventId = Number(idParam);
      this.loading.set(true);
      this.eventService.get(this.eventId).subscribe({
        next: (event) => {
          this.title = event.title;
          this.description = event.description ?? '';
          this.startsAt = toDatetimeLocal(event.startsAt);
          this.endsAt = event.endsAt ? toDatetimeLocal(event.endsAt) : '';
          this.loadSelectedChurches(event.churchIds);
        },
        error: () => this.loading.set(false),
      });
    } else if (preselectChurchId) {
      this.loadSelectedChurches([Number(preselectChurchId)]);
    }
  }

  private loadSelectedChurches(churchIds: number[]) {
    if (churchIds.length === 0) {
      this.loading.set(false);
      return;
    }
    forkJoin(churchIds.map((id) => this.churchService.getById(id).pipe(catchError(() => of(null))))).subscribe(
      (churches) => {
        this.selectedChurches.set(churches.filter((c): c is Church => c !== null));
        this.loading.set(false);
      }
    );
  }

  onChurchQueryChange() {
    this.churchQueryChanged.next(this.churchQuery);
  }

  addChurch(church: Church) {
    this.selectedChurches.set([...this.selectedChurches(), church]);
    this.churchResults.set(this.churchResults().filter((c) => c.id !== church.id));
    this.churchQuery = '';
  }

  removeChurch(church: Church) {
    this.selectedChurches.set(this.selectedChurches().filter((c) => c.id !== church.id));
  }

  get canSubmit(): boolean {
    return !!this.title.trim() && !!this.startsAt && this.selectedChurches().length > 0 && !this.saving();
  }

  save() {
    if (!this.canSubmit) return;

    this.saving.set(true);
    this.saveError.set('');

    const event: ChurchEvent = {
      title: this.title.trim(),
      description: this.description.trim() || undefined,
      startsAt: new Date(this.startsAt).toISOString(),
      endsAt: this.endsAt ? new Date(this.endsAt).toISOString() : undefined,
      churchIds: this.selectedChurches().map((c) => c.id!),
    };

    const obs = this.isEdit && this.eventId ? this.eventService.update(this.eventId, event) : this.eventService.create(event);

    obs.subscribe({
      next: (saved) => this.router.navigate(['/events', saved.id]),
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(err.error?.error ?? 'Could not save this event. Try again.');
      },
    });
  }
}

/** "2026-09-17T14:00:00Z" -> "2026-09-17T14:00" for <input type="datetime-local"> */
function toDatetimeLocal(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
