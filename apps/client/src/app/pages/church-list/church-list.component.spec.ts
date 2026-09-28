import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { ChurchListComponent } from './church-list.component';
import { ChurchService } from '../../services/church.service';

const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };

describe('ChurchListComponent', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [ChurchListComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ChurchListComponent);
    fixture.detectChanges();
    http.expectOne((req) => req.url === '/api/churches').flush(emptyPage);
    return { http, fixture, component: fixture.componentInstance };
  }

  /** See ChurchService.resetBrowse$ / app.ts browseClick() — clicking "Browse" in
   *  the nav while already on this page is a same-URL navigation the Router
   *  ignores, so app.ts resets this component directly instead of relying on a
   *  navigation that will never actually fire. */
  it('clears every active filter and re-searches when the nav asks it to reset', () => {
    const { http, component } = setup();
    component.query = 'Grace Baptist';
    component.denomination = 'Baptist';
    component.city = 'Austin';
    component.state = 'TX';
    component.filtersOpen.set(true);

    TestBed.inject(ChurchService).resetBrowse();

    expect(component.query).toBe('');
    expect(component.denomination).toBe('');
    expect(component.city).toBe('');
    expect(component.state).toBe('');
    expect(component.filtersOpen()).toBe(false);

    const req = http.expectOne((r) => r.url === '/api/churches');
    expect(req.request.params.get('query')).toBeNull();
    expect(req.request.params.get('denomination')).toBeNull();
    expect(req.request.params.get('city')).toBeNull();
    expect(req.request.params.get('state')).toBeNull();
    req.flush(emptyPage);
    http.verify();
  });

  it('stops listening once the component is destroyed (no leaked subscription across page visits)', () => {
    const { http, fixture } = setup();
    fixture.destroy();

    TestBed.inject(ChurchService).resetBrowse();

    http.verify(); // no further request — a destroyed instance didn't react
  });

  /** Regression coverage for a real bug: "Near Me" used to call a separate
   *  GET /churches/nearest endpoint that ignored denomination/city/state entirely and
   *  capped results to a flat limit (default 5) — so a denomination filter combined
   *  with Near Me silently searched every denomination, and most nearby churches never
   *  showed up at all. Near Me now routes through the same GET /churches search()
   *  endpoint (via knownCoords), so it inherits every filter and full pagination. */
  describe('Near Me', () => {
    function mockGeolocation(lat: number, lng: number) {
      (globalThis as any).navigator.geolocation = {
        getCurrentPosition: (success: PositionCallback) =>
          success({ coords: { latitude: lat, longitude: lng } } as GeolocationPosition),
      };
    }

    it('searches with lat/lng instead of the old separate nearest endpoint', () => {
      const { http, component } = setup();
      mockGeolocation(30.2672, -97.7431);

      component.nearMe();

      const req = http.expectOne((r) => r.url === '/api/churches');
      expect(req.request.params.get('lat')).toBe('30.2672');
      expect(req.request.params.get('lng')).toBe('-97.7431');
      req.flush(emptyPage);
      http.verify();
    });

    it('keeps the denomination filter once results come back — the reported bug', () => {
      const { http, component } = setup();
      component.denomination = 'Baptist';
      mockGeolocation(30.2672, -97.7431);

      component.nearMe();

      const req = http.expectOne((r) => r.url === '/api/churches');
      expect(req.request.params.get('denomination')).toBe('Baptist');
      expect(req.request.params.get('lat')).toBe('30.2672');
      req.flush(emptyPage);
      http.verify();
    });

    it('stays in Near Me mode when a filter changes afterward, since geo ordering only turns off for a real text query', () => {
      jest.useFakeTimers();
      try {
        const { http, component } = setup();
        mockGeolocation(30.2672, -97.7431);
        component.nearMe();
        http.expectOne((r) => r.url === '/api/churches').flush(emptyPage);

        component.selectDenomination('Catholic'); // funnels through the 300ms-debounced filtersChanged subject
        expect(component.nearMeActive()).toBe(true);
        jest.advanceTimersByTime(300);

        const req = http.expectOne((r) => r.url === '/api/churches');
        expect(req.request.params.get('lat')).toBe('30.2672'); // still geo-sorted
        expect(req.request.params.get('denomination')).toBe('Catholic');
        req.flush(emptyPage);
        http.verify();
      } finally {
        jest.useRealTimers();
      }
    });

    it('drops out of Near Me mode once a text search query is entered', () => {
      jest.useFakeTimers();
      try {
        const { http, component } = setup();
        mockGeolocation(30.2672, -97.7431);
        component.nearMe();
        http.expectOne((r) => r.url === '/api/churches').flush(emptyPage);

        component.query = 'Grace';
        component.onFiltersChanged();
        expect(component.nearMeActive()).toBe(false);
        jest.advanceTimersByTime(300);

        http.expectOne((r) => r.url === '/api/churches').flush(emptyPage);
        http.verify();
      } finally {
        jest.useRealTimers();
      }
    });

    it('paginates past the first page instead of stopping at a fixed cap', () => {
      const { http, component } = setup();
      mockGeolocation(30.2672, -97.7431);
      component.nearMe();
      http
        .expectOne((r) => r.url === '/api/churches')
        .flush({ content: [{ id: 1, name: 'A' }], totalElements: 40, totalPages: 2, number: 0, size: 20 });

      component.loadMore();

      const req = http.expectOne((r) => r.url === '/api/churches');
      expect(req.request.params.get('page')).toBe('1');
      req.flush(emptyPage);
      http.verify();
    });

    it('computes distance in miles from the known location to a geocoded church', () => {
      const { http, component } = setup();
      mockGeolocation(30.2672, -97.7431); // Austin, TX
      component.nearMe();
      http.expectOne((r) => r.url === '/api/churches').flush(emptyPage);

      // Houston, TX — roughly 150-165 miles from Austin depending on the exact points used
      const distance = component.distanceMiles({ id: 1, name: 'Houston Church', latitude: 29.7604, longitude: -95.3698 });

      expect(distance).not.toBeNull();
      expect(distance).toBeGreaterThan(140);
      expect(distance).toBeLessThan(170);
    });

    it('returns null distance for a church with no coordinates rather than a misleading 0', () => {
      const { http, component } = setup();
      mockGeolocation(30.2672, -97.7431);
      component.nearMe();
      http.expectOne((r) => r.url === '/api/churches').flush(emptyPage);

      expect(component.distanceMiles({ id: 1, name: 'Ungeocoded Church' })).toBeNull();
    });

    it('returns null distance before any location is known', () => {
      const { component } = setup();
      expect(component.distanceMiles({ id: 1, name: 'A', latitude: 30, longitude: -97 })).toBeNull();
    });
  });

  /** A deep link from Learn's "Find {tradition} churches" (learn.component.ts
   *  denominationFilter()) pre-selects a denomination via ?denomination=. Without
   *  this, that link would look like a filter but silently do nothing — the same
   *  class of bug as the "Browse does nothing" fix above. */
  describe('deep-linked denomination filter', () => {
    function setupWithQueryParam(denomination: string) {
      TestBed.configureTestingModule({
        imports: [ChurchListComponent],
        providers: [
          provideRouter([]),
          provideHttpClient(),
          provideHttpClientTesting(),
          { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: (key: string) => (key === 'denomination' ? denomination : null) } } } },
        ],
      });
      const http = TestBed.inject(HttpTestingController);
      const fixture = TestBed.createComponent(ChurchListComponent);
      fixture.detectChanges();
      return { http, component: fixture.componentInstance };
    }

    it('pre-selects a valid curated denomination from the URL on the very first search', () => {
      const { http, component } = setupWithQueryParam('Lutheran');
      expect(component.denomination).toBe('Lutheran');
      const req = http.expectOne((r) => r.url === '/api/churches');
      expect(req.request.params.get('denomination')).toBe('Lutheran');
      req.flush(emptyPage);
      http.verify();
    });

    it('ignores a query param value that is not one of the curated DENOMINATIONS, rather than sending garbage to the server', () => {
      const { http, component } = setupWithQueryParam('Not A Real Denomination');
      expect(component.denomination).toBe('');
      const req = http.expectOne((r) => r.url === '/api/churches');
      expect(req.request.params.get('denomination')).toBeNull();
      req.flush(emptyPage);
      http.verify();
    });
  });

  /** The homepage hero's "Find a church" CTA — see the product-review note that a
   *  first-time visitor should understand the product in one screen without the
   *  directory search becoming a second, separate destination to click through to. */
  describe('hero', () => {
    it('renders the value-proposition headline and both CTAs above the existing directory', () => {
      const { fixture } = setup();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Find a church. Understand what it believes.');
      expect(el.querySelector('button')?.textContent).toContain('Find a church');
      const learnLink = Array.from(el.querySelectorAll('a')).find((a) => a.textContent?.includes('Explore beliefs'));
      expect(learnLink?.getAttribute('href')).toBe('/learn');
    });

    it('focusSearch() scrolls to and focuses the existing search input rather than navigating anywhere new', () => {
      const { fixture, component } = setup();
      const input = fixture.nativeElement.querySelector('input[placeholder*="Search"]') as HTMLInputElement;
      // jsdom doesn't implement scrollIntoView at all — stub it directly rather
      // than jest.spyOn, which requires the method to already exist.
      input.scrollIntoView = jest.fn();
      const focusSpy = jest.spyOn(input, 'focus');

      component.focusSearch();

      expect(input.scrollIntoView).toHaveBeenCalled();
      expect(focusSpy).toHaveBeenCalled();
    });
  });

  /** Connects Browse back to Learn (the doc's "product loop") without letting it
   *  dominate the homepage — only appears once there are results to show it after,
   *  never on the loading/error/empty states. */
  describe('Learn teaser', () => {
    function setupWithResults() {
      TestBed.configureTestingModule({
        imports: [ChurchListComponent],
        providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
      });
      const http = TestBed.inject(HttpTestingController);
      const fixture = TestBed.createComponent(ChurchListComponent);
      fixture.detectChanges();
      http
        .expectOne((req) => req.url === '/api/churches')
        .flush({ content: [{ id: 1, name: 'Grace Church' }], totalElements: 1, totalPages: 1, number: 0, size: 20 });
      fixture.detectChanges();
      return { el: fixture.nativeElement as HTMLElement };
    }

    it('appears after real results, linking to both traditions and the quiz', () => {
      const { el } = setupWithResults();
      expect(el.textContent).toContain('Not sure what you’re looking for?');
      const links = Array.from(el.querySelectorAll('a')).map((a) => a.textContent?.trim());
      expect(links).toEqual(expect.arrayContaining([expect.stringContaining('Explore traditions'), expect.stringContaining('Take the quiz')]));
    });

    it('does not appear when there are no results yet', () => {
      const { fixture } = setup(); // default setup() flushes an empty page
      expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Not sure what you’re looking for?');
    });
  });
});
