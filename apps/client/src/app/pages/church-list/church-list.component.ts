import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import { Church, DENOMINATIONS } from '../../models/church.model';
import { ChurchService } from '../../services/church.service';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-church-list',
  standalone: true,
  imports: [RouterModule, FormsModule],
  templateUrl: './church-list.component.html',
})
export class ChurchListComponent implements AfterViewInit, OnDestroy {
  private churchService = inject(ChurchService);

  @ViewChild('sentinel') sentinel?: ElementRef<HTMLElement>;
  private observer?: IntersectionObserver;

  readonly DENOMINATIONS = DENOMINATIONS;

  results = signal<Church[]>([]);
  page = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);

  loading = signal(true); // full-page: first load of a fresh query
  loadingMore = signal(false); // inline: appending another page
  error = signal(false);
  filtersOpen = signal(false);
  nearMeActive = signal(false);
  locating = signal(false);
  locateError = signal('');

  query = '';
  denomination = '';
  city = '';
  state = '';

  private filtersChanged = new Subject<string>();
  private nearMeCoords: { lat: number; lng: number } | null = null;

  constructor() {
    this.filtersChanged.pipe(debounceTime(300), distinctUntilChanged()).subscribe(() => this.runSearch(true));
    this.runSearch(true);
  }

  ngAfterViewInit() {
    if (!this.sentinel) return;
    this.observer = new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting) this.loadMore();
    });
    this.observer.observe(this.sentinel.nativeElement);
  }

  ngOnDestroy() {
    this.observer?.disconnect();
  }

  /** Any text/filter change funnels through here, debounced, and always resets to page 0. */
  onFiltersChanged() {
    this.nearMeActive.set(false);
    this.filtersChanged.next(JSON.stringify([this.query, this.denomination, this.city, this.state]));
  }

  private runSearch(reset: boolean) {
    const nextPage = reset ? 0 : this.page() + 1;

    if (reset) {
      this.loading.set(true);
      this.error.set(false);
    } else {
      this.loadingMore.set(true);
    }

    this.churchService
      .search({
        query: this.query.trim() || undefined,
        denomination: this.denomination || undefined,
        city: this.city.trim() || undefined,
        state: this.state.trim() || undefined,
        page: nextPage,
        size: PAGE_SIZE,
      })
      .subscribe({
        next: (result) => {
          this.results.set(reset ? result.content : [...this.results(), ...result.content]);
          this.page.set(result.number);
          this.totalPages.set(result.totalPages);
          this.totalElements.set(result.totalElements);
          this.loading.set(false);
          this.loadingMore.set(false);
        },
        error: () => {
          this.error.set(true);
          this.loading.set(false);
          this.loadingMore.set(false);
        },
      });
  }

  loadMore() {
    if (this.loading() || this.loadingMore() || this.nearMeActive()) return;
    if (this.page() + 1 >= this.totalPages()) return;
    this.runSearch(false);
  }

  nearMe() {
    if (!('geolocation' in navigator)) {
      this.locateError.set('This browser does not support location services.');
      return;
    }

    this.locating.set(true);
    this.locateError.set('');

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const { latitude, longitude } = position.coords;
        this.nearMeCoords = { lat: latitude, lng: longitude };
        this.locating.set(false);
        this.nearMeActive.set(true);
        this.loading.set(true);
        this.error.set(false);

        this.churchService.nearest(latitude, longitude).subscribe({
          next: (churches) => {
            this.results.set(churches);
            this.page.set(0);
            this.totalPages.set(1);
            this.totalElements.set(churches.length);
            this.loading.set(false);
          },
          error: () => {
            this.error.set(true);
            this.loading.set(false);
          },
        });
      },
      () => {
        this.locating.set(false);
        this.locateError.set('Location access was denied. Enable location for this site and try again.');
      },
      { timeout: 10_000 }
    );
  }

  clearNearMe() {
    this.nearMeActive.set(false);
    this.nearMeCoords = null;
    this.runSearch(true);
  }

  toggleFilters() {
    this.filtersOpen.set(!this.filtersOpen());
  }

  initials(name: string): string {
    return name
      .split(' ')
      .filter((w) => w.length > 2)
      .slice(0, 2)
      .map((w) => w[0])
      .join('');
  }

  denominationBadge(denomination?: string): string {
    const map: Record<string, string> = {
      'Catholic': 'bg-purple-100 text-purple-700',
      'Baptist': 'bg-blue-100 text-blue-700',
      'Methodist': 'bg-teal-100 text-teal-700',
      'Lutheran': 'bg-orange-100 text-orange-700',
      'Presbyterian': 'bg-green-100 text-green-700',
      'Episcopal/Anglican': 'bg-red-100 text-red-700',
      'Pentecostal / Assembly of God': 'bg-amber-100 text-amber-700',
      'Non-denominational': 'bg-slate-100 text-slate-700',
      'Evangelical': 'bg-sky-100 text-sky-700',
    };
    return denomination ? (map[denomination] ?? 'bg-indigo-100 text-indigo-700') : 'bg-gray-100 text-gray-600';
  }

  avatarBg(denomination?: string): string {
    const map: Record<string, string> = {
      'Catholic': 'bg-purple-600',
      'Baptist': 'bg-blue-600',
      'Methodist': 'bg-teal-600',
      'Lutheran': 'bg-orange-600',
      'Presbyterian': 'bg-green-600',
      'Episcopal/Anglican': 'bg-red-600',
      'Pentecostal / Assembly of God': 'bg-amber-600',
      'Non-denominational': 'bg-slate-600',
      'Evangelical': 'bg-sky-600',
    };
    return denomination ? (map[denomination] ?? 'bg-indigo-600') : 'bg-slate-500';
  }
}
