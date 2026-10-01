import { DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  Component,
  OnInit,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { FaithMatch, FaithSaved } from '../../models/faith.model';
import { FaithService, faithError } from '../../services/faith.service';
import { AuthService } from '../../services/auth.service';
import { MatchCardComponent } from './match-card.component';

@Component({
  selector: 'app-faith-results',
  standalone: true,
  imports: [FormsModule, RouterModule, MatchCardComponent],
  templateUrl: './results.component.html',
  styleUrl: './faith.scss',
})
export class ResultsComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  service = inject(FaithService);
  auth = inject(AuthService);
  private router = inject(Router);
  city = '';
  state = '';
  radius = 40;
  busy = signal(false);
  error = signal('');
  notice = signal('');
  saved = signal<FaithSaved[]>([]);
  constructor() {
    effect(() => {
      const user = this.auth.currentUser();
      untracked(() => {
        this.saved.set([]);
        if (user) this.loadSaved();
      });
    });
  }
  ngOnInit() {
    const req = this.service.request();
    this.city = req?.city ?? '';
    this.state = req?.state ?? '';
    this.radius = req?.radiusKm ?? 40;
  }
  loadSaved() {
    const userId = this.auth.currentUser()?.id;
    if (userId === undefined) return;
    this.service
      .saved()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (this.auth.currentUser()?.id === userId) this.saved.set(rows);
        },
        error: (e) => {
          if (this.auth.currentUser()?.id === userId)
            this.error.set(faithError(e, 'quiz'));
        },
      });
  }
  findChurches(nearby = false) {
    const req = this.service.request();
    if (!req) return;
    this.error.set('');
    this.notice.set('');
    if (!nearby && (!this.city.trim() || !this.state.trim())) {
      this.error.set('Enter both a city and a state, or use your location.');
      return;
    }
    this.busy.set(true);
    const evaluate = (lat?: number, lng?: number) => {
      const request = {
        ...req,
        city: nearby ? '' : this.city.trim(),
        state: nearby ? '' : this.state.trim(),
        latitude: lat,
        longitude: lng,
        radiusKm: Number(this.radius),
      };
      this.service
        .evaluate(request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (r) => {
            this.service.request.set(request);
            this.service.result.set(r);
            this.busy.set(false);
          },
          error: (e) => {
            this.error.set(faithError(e, 'quiz'));
            this.busy.set(false);
          },
        });
    };
    if (nearby) {
      if (!navigator.geolocation) {
        this.error.set(
          'Location is unavailable. Enter a city and state instead.',
        );
        this.busy.set(false);
        return;
      }
      navigator.geolocation.getCurrentPosition(
        (p) => evaluate(p.coords.latitude, p.coords.longitude),
        () => {
          this.error.set(
            'Location could not be read. Enter a city and state instead.',
          );
          this.busy.set(false);
        },
        { timeout: 10000 },
      );
    } else evaluate();
  }
  save() {
    const req = this.service.request();
    if (!req) return;
    this.busy.set(true);
    this.error.set('');
    this.service
      .save(req)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notice.set('Saved privately to your account.');
          this.busy.set(false);
          this.loadSaved();
        },
        error: (e) => {
          this.error.set(faithError(e, 'quiz'));
          this.busy.set(false);
        },
      });
  }
  open(s: FaithSaved) {
    this.service.request.set(s.request);
    this.service.result.set(s.result);
    this.city = s.request.city ?? '';
    this.state = s.request.state ?? '';
    this.notice.set(
      'Viewing a saved snapshot from ' +
        s.createdAt.slice(0, 10) +
        '. Refresh results to include churches that have published since.',
    );
  }
  remove(s: FaithSaved) {
    this.service
      .delete(s.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () =>
          this.saved.update((rows) => rows.filter((x) => x.id !== s.id)),
        error: (e) => this.error.set(faithError(e, 'quiz')),
      });
  }
  recalculate() {
    const req = this.service.request();
    if (!req) return;
    this.busy.set(true);
    this.service
      .evaluate(req)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (r) => {
          this.service.result.set(r);
          this.notice.set(
            'Results refreshed with the latest church information.',
          );
          this.busy.set(false);
        },
        error: (e) => {
          this.error.set(faithError(e, 'quiz'));
          this.busy.set(false);
        },
      });
  }
  group(matches: FaithMatch[], conflicts: boolean) {
    return matches.filter(
      (m) => (m.status === 'essential_conflict') === conflicts,
    );
  }
  /** Traditions sharing none of the user's answered topics get a one-line mention
   *  instead of a full card of "Unknown"s. 'explore' (no weighted answers at all)
   *  stays a card: there, every tradition is equally uncompared, by design. */
  comparable(matches: FaithMatch[]) {
    return matches.filter((m) => m.status === 'explore' || m.comparedTopics > 0);
  }
  notComparable(matches: FaithMatch[]) {
    return matches.filter((m) => m.status !== 'explore' && m.comparedTopics === 0);
  }
  clear() {
    this.service.clear();
    this.router.navigate(['/learn']);
  }
}
