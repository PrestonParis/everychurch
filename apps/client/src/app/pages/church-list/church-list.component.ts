import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { Church, DENOMINATIONS } from '../../models/church.model';
import { ChurchService } from '../../services/church.service';
import { FavoriteButtonComponent } from '../../components/favorite-button.component';
import { TraditionEmblemComponent } from '../../components/tradition-emblem.component';
import { denomStyle, tintColor, titleCase } from '../../utils/church-display.util';

const PAGE_SIZE = 20;
const SEED_STORAGE_KEY = 'ec_browse_seed';

@Component({
  selector: 'app-church-list',
  standalone: true,
  imports: [RouterModule, FormsModule, FavoriteButtonComponent, TraditionEmblemComponent, DecimalPipe],
  templateUrl: './church-list.component.html',
})
export class ChurchListComponent implements AfterViewInit, OnDestroy {
  private churchService = inject(ChurchService);
  private destroyRef = inject(DestroyRef);
  private route = inject(ActivatedRoute);

  @ViewChild('sentinel') sentinel?: ElementRef<HTMLElement>;
  @ViewChild('searchInput') searchInput?: ElementRef<HTMLInputElement>;
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
  sortMenuOpen = signal(false);
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

  /** Per-browser-session shuffle seed for the default (no-query) result order — see ChurchRepository.search. */
  private readonly sessionSeed = this.getOrCreateSeed();

  constructor() {
    // A deep link from elsewhere in the app (e.g. Learn's "Find {tradition}
    // churches" — see denominationFilter() there) pre-selects a denomination.
    // Read once at construction, not reactively — a query param is a starting
    // point for this visit, not something that should keep fighting the user's
    // own filter changes afterward.
    const initialDenomination = this.route.snapshot.queryParamMap.get('denomination');
    if (initialDenomination && (this.DENOMINATIONS as readonly string[]).includes(initialDenomination)) {
      this.denomination = initialDenomination;
    }

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

    // See ChurchService.resetBrowse$ — clicking "Browse" in the nav while already
    // on this page is a same-URL routerLink navigation, which the Router ignores,
    // so app.ts asks us to reset directly instead.
    this.churchService.resetBrowse$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.resetToDefault());
  }

  /** Clears every active filter/search and goes back to the default, unfiltered
   *  browse view — see the resetBrowse$ subscription above. */
  resetToDefault() {
    this.query = '';
    this.denomination = '';
    this.city = '';
    this.state = '';
    this.nearMeActive.set(false);
    this.knownCoords.set(null);
    this.locateError.set('');
    this.filtersOpen.set(false);
    this.denomDropdownOpen.set(false);
    this.cityDropdownOpen.set(false);
    this.stateDropdownOpen.set(false);
    this.sortMenuOpen.set(false);
    this.runSearch(true);
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

  /** Any text/filter change funnels through here, debounced, and always resets to page 0.
   *  Only a non-blank query drops out of "Near Me" — repo.search only disables geo
   *  ordering when :query != '' (see ChurchRepository.search's ORDER BY), so changing
   *  denomination/city/state while Near Me is active should keep it active: the results
   *  stay sorted by distance (knownCoords still feeds every search call below) and just
   *  get filtered further, rather than silently relabeling back to "Recommended" while
   *  secretly still geo-sorted. */
  onFiltersChanged() {
    if (this.query.trim()) this.nearMeActive.set(false);
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
    if (this.loading() || this.loadingMore()) return;
    if (this.page() + 1 >= this.totalPages()) return;
    this.runSearch(false);
  }

  /** Routes through the same search()/runSearch() path as passive geo-sorting
   *  (knownCoords) rather than the old separate GET /churches/nearest — that endpoint
   *  ignored denomination/city/state/query entirely (a denomination filter combined
   *  with "Near Me" silently searched all denominations), capped results to a flat
   *  `limit` (default 5, so most matching churches never showed up), and never
   *  returned enough to compute a distance from. repo.search's geo_point <-> ORDER BY
   *  already ANDs in every filter and paginates normally — Near Me just needed to
   *  feed it real (freshly-prompted, not silently-known) coordinates. */
  nearMe() {
    if (!('geolocation' in navigator)) {
      this.locateError.set('This browser does not support location services.');
      return;
    }

    this.locating.set(true);
    this.locateError.set('');

    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.knownCoords.set({ lat: position.coords.latitude, lng: position.coords.longitude });
        this.locating.set(false);
        this.nearMeActive.set(true);
        this.runSearch(true);
      },
      () => {
        this.locating.set(false);
        this.locateError.set('Location access was denied. Enable location for this site and try again.');
      },
      { timeout: 10_000 }
    );
  }

  /** Turns off the "Nearest"/"Closest Churches" labeling and re-sorts, but
   *  deliberately leaves knownCoords set — once location's been shared, default
   *  browsing stays sorted nearest-first (see tryPassiveLocation) rather than
   *  reverting to the shuffled order. */
  clearNearMe() {
    this.nearMeActive.set(false);
    this.runSearch(true);
  }

  /** Haversine distance in miles from the user's known location (see knownCoords) to
   *  a church's geocoded coordinates — null when either side is missing (no location
   *  yet, or the church hasn't been geocoded), so the template can hide the badge
   *  rather than show a false "0 mi away". */
  distanceMiles(church: Church): number | null {
    const coords = this.knownCoords();
    if (!coords || church.latitude == null || church.longitude == null) return null;

    const EARTH_RADIUS_MI = 3958.8;
    const dLat = this.toRadians(church.latitude - coords.lat);
    const dLng = this.toRadians(church.longitude - coords.lng);
    const a =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(this.toRadians(coords.lat)) * Math.cos(this.toRadians(church.latitude)) * Math.sin(dLng / 2) ** 2;
    return EARTH_RADIUS_MI * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  private toRadians(degrees: number): number {
    return (degrees * Math.PI) / 180;
  }

  toggleFilters() {
    this.filtersOpen.set(!this.filtersOpen());
  }

  /** The hero's "Find a church" CTA — scrolls the existing search bar into view
   *  and focuses it, rather than being yet another button that navigates
   *  somewhere. There's nowhere else to go: the directory this scrolls to is
   *  already the whole page. */
  focusSearch() {
    this.searchInput?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
    this.searchInput?.nativeElement.focus();
  }

  toggleSortMenu() {
    this.sortMenuOpen.set(!this.sortMenuOpen());
  }

  closeSortMenu() {
    this.sortMenuOpen.set(false);
  }

  /** Both options are real, existing ordering modes (see runSearch/nearMe) — no
   *  sort field this app doesn't actually support (e.g. alphabetical would undo the
   *  earlier fix for numerically-named churches always dominating page one). */
  selectSort(mode: 'recommended' | 'nearest') {
    this.sortMenuOpen.set(false);
    if (mode === 'nearest') {
      if (!this.nearMeActive()) this.nearMe();
    } else if (this.nearMeActive()) {
      this.clearNearMe();
    }
  }

  titleCase = titleCase;

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

  /** Curated per-tradition icon + color and its tint helper — shared with the church
   *  profile hero (see utils/church-display.util) so a tradition reads as the same
   *  color everywhere in the app. */
  denomStyle = denomStyle;
  tint = tintColor;
}
