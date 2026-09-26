import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { Church, DENOMINATIONS } from '../../models/church.model';
import { ChurchService } from '../../services/church.service';
import { FavoriteButtonComponent } from '../../components/favorite-button.component';

const PAGE_SIZE = 20;
const SEED_STORAGE_KEY = 'ec_browse_seed';

@Component({
  selector: 'app-church-list',
  standalone: true,
  imports: [RouterModule, FormsModule, FavoriteButtonComponent],
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

  denomDropdownOpen = signal(false);
  cityDropdownOpen = signal(false);
  stateDropdownOpen = signal(false);
  citySuggestions = signal<string[]>([]);
  stateSuggestions = signal<string[]>([]);

  /** Silently known location (a prior geolocation grant, not a fresh prompt) — see tryPassiveLocation(). Sorts default browsing nearest-first instead of shuffled. */
  knownCoords = signal<{ lat: number; lng: number } | null>(null);

  query = '';
  denomination = '';
  city = '';
  state = '';

  private filtersChanged = new Subject<string>();
  private citySuggest = new Subject<string>();
  private stateSuggest = new Subject<string>();
  private nearMeCoords: { lat: number; lng: number } | null = null;

  /** Per-browser-session shuffle seed for the default (no-query) result order — see ChurchRepository.search. */
  private readonly sessionSeed = this.getOrCreateSeed();

  constructor() {
    this.filtersChanged.pipe(debounceTime(300), distinctUntilChanged()).subscribe(() => this.runSearch(true));

    this.citySuggest
      .pipe(
        debounceTime(200),
        switchMap((prefix) => this.churchService.suggestCities(prefix, this.state))
      )
      .subscribe((suggestions) => this.citySuggestions.set(suggestions));

    this.stateSuggest
      .pipe(
        debounceTime(200),
        switchMap((prefix) => this.churchService.suggestStates(prefix))
      )
      .subscribe((suggestions) => this.stateSuggestions.set(suggestions));

    this.runSearch(true);
    this.tryPassiveLocation();
  }

  private getOrCreateSeed(): string {
    try {
      const existing = sessionStorage.getItem(SEED_STORAGE_KEY);
      if (existing) return existing;
      const seed = crypto.randomUUID();
      sessionStorage.setItem(SEED_STORAGE_KEY, seed);
      return seed;
    } catch {
      return Math.random().toString(36).slice(2); // sessionStorage unavailable (private browsing, etc.) — still works for this page load
    }
  }

  /** Reuses a location permission already granted in a prior visit — never prompts. Falls back to the seeded shuffle otherwise. */
  private tryPassiveLocation() {
    if (!('permissions' in navigator) || !('geolocation' in navigator)) return;
    navigator.permissions
      .query({ name: 'geolocation' })
      .then((status) => {
        if (status.state !== 'granted') return;
        navigator.geolocation.getCurrentPosition(
          (position) => {
            this.knownCoords.set({ lat: position.coords.latitude, lng: position.coords.longitude });
            if (!this.nearMeActive()) this.runSearch(true);
          },
          () => {}, // silently ignore — falls back to the seeded shuffle
          { timeout: 5_000 }
        );
      })
      .catch(() => {}); // Permissions API doesn't support the 'geolocation' name in this browser — silently fall back
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

    const coords = this.knownCoords();

    this.churchService
      .search({
        query: this.query.trim() || undefined,
        denomination: this.denomination || undefined,
        city: this.city.trim() || undefined,
        state: this.state.trim() || undefined,
        seed: this.sessionSeed,
        lat: coords?.lat,
        lng: coords?.lng,
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

  browseDenomination(d: string) {
    this.denomination = d;
    this.onFiltersChanged();
  }

  clearDenomination() {
    this.denomination = '';
    this.onFiltersChanged();
  }

  toggleDenomDropdown() {
    this.denomDropdownOpen.set(!this.denomDropdownOpen());
  }

  closeDenomDropdown() {
    this.denomDropdownOpen.set(false);
  }

  selectDenomination(d: string) {
    this.denomination = d;
    this.denomDropdownOpen.set(false);
    this.onFiltersChanged();
  }

  onCityInput() {
    this.cityDropdownOpen.set(true);
    this.citySuggest.next(this.city.trim());
    this.onFiltersChanged();
  }

  selectCitySuggestion(value: string) {
    this.city = value;
    this.cityDropdownOpen.set(false);
    this.onFiltersChanged();
  }

  closeCityDropdown() {
    this.cityDropdownOpen.set(false);
  }

  onStateInput() {
    this.stateDropdownOpen.set(true);
    this.stateSuggest.next(this.state.trim());
    this.onFiltersChanged();
  }

  selectStateSuggestion(value: string) {
    this.state = value;
    this.stateDropdownOpen.set(false);
    this.onFiltersChanged();
  }

  closeStateDropdown() {
    this.stateDropdownOpen.set(false);
  }

  /**
   * Curated per-tradition icon + color, grouped by shared visual family (cross /
   * flame / book / quatrefoil) and differentiated by hue. Bulk-imported/scraped data
   * stores denomination as raw abbreviations ("SBC", "NBC", "COC") rather than the
   * curated DENOMINATIONS labels, so classification below is keyword/substring based
   * to catch both forms.
   */
  private readonly DENOM_STYLES: Record<string, { color: string; icon: DenomIcon }> = {
    catholic: { color: '#7A2331', icon: 'cross' },
    baptist: { color: '#1F5C57', icon: 'drop' },
    churchOfChrist: { color: '#3E4C5C', icon: 'book' },
    orthodox: { color: '#5B3159', icon: 'orthodoxCross' },
    episcopal: { color: '#2E4057', icon: 'crossCircle' },
    evangelical: { color: '#A4542E', icon: 'flame' },
    lds: { color: '#7A5C1E', icon: 'star' },
    lutheran: { color: '#5B3A5C', icon: 'cross' },
    methodist: { color: '#B8792E', icon: 'flame' },
    nonDenom: { color: '#6B6258', icon: 'quatrefoil' },
    pentecostal: { color: '#B5451F', icon: 'flame' },
    presbyterian: { color: '#3F6B4A', icon: 'cross' },
    adventist: { color: '#2E6B5E', icon: 'book' },
    other: { color: '#8C8478', icon: 'quatrefoil' },
  };

  denomStyle(denomination?: string): { color: string; icon: DenomIcon } {
    const d = (denomination ?? '').toLowerCase();
    const key =
      (d.includes('cath') && 'catholic') ||
      ((d.includes('bapt') || d.includes('sbc') || d.includes('nbc')) && 'baptist') ||
      (d.includes('coc') && 'churchOfChrist') ||
      (d.includes('orthodox') && 'orthodox') ||
      ((d.includes('episcop') || d.includes('anglic')) && 'episcopal') ||
      (d.includes('evangel') && 'evangelical') ||
      ((d.includes('latter-day') || d.includes('lds') || d.includes('mormon')) && 'lds') ||
      (d.includes('luther') && 'lutheran') ||
      (d.includes('method') && 'methodist') ||
      ((d.includes('non-denom') || d.includes('nondenom')) && 'nonDenom') ||
      ((d.includes('pentecost') || d.includes('assembl')) && 'pentecostal') ||
      (d.includes('presbyter') && 'presbyterian') ||
      ((d.includes('advent') || d === 'sda') && 'adventist') ||
      'other';
    return this.DENOM_STYLES[key];
  }

  /** Tints a curated hex color for the badge chip's background (e.g. 14% alpha). */
  tint(hex: string, alpha: number): string {
    const n = parseInt(hex.slice(1), 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
  }
}

type DenomIcon = 'cross' | 'orthodoxCross' | 'crossCircle' | 'drop' | 'book' | 'flame' | 'star' | 'quatrefoil';
