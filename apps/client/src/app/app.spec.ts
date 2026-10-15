import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { App } from './app';
import { ChurchService } from './services/church.service';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient()],
    }).compileComponents();
  });

  it('renders the EveryChurch nav', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('nav')?.textContent).toContain('EveryChurch');
  });

  it('credits OpenStreetMap site-wide, as the ODbL license on imported churches requires', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('footer a[href="https://www.openstreetmap.org/copyright"]');
    expect(link?.textContent).toContain('© OpenStreetMap contributors');
  });

  /** Browse's routerLink target (/churches) is also this app's default route, so
   *  clicking it while already there is a same-URL navigation the Router ignores
   *  outright (no NavigationEnd fires) — any active search/filters on the church
   *  list just sit there, looking like the click did nothing. See
   *  ChurchService.resetBrowse$. */
  describe('browseClick', () => {
    it('resets the church list directly when already on /churches, since routerLink alone would no-op', () => {
      const fixture = TestBed.createComponent(App);
      const router = TestBed.inject(Router);
      const resetSpy = jest.spyOn(TestBed.inject(ChurchService), 'resetBrowse');
      jest.spyOn(router, 'url', 'get').mockReturnValue('/churches');

      fixture.componentInstance.browseClick();

      expect(resetSpy).toHaveBeenCalledTimes(1);
    });

    it('does nothing when clicked from a different page — routerLink alone handles a real navigation fine', () => {
      const fixture = TestBed.createComponent(App);
      const router = TestBed.inject(Router);
      const resetSpy = jest.spyOn(TestBed.inject(ChurchService), 'resetBrowse');
      jest.spyOn(router, 'url', 'get').mockReturnValue('/learn');

      fixture.componentInstance.browseClick();

      expect(resetSpy).not.toHaveBeenCalled();
    });

    it('still resets when the current URL has query params (e.g. /churches?city=Austin)', () => {
      const fixture = TestBed.createComponent(App);
      const router = TestBed.inject(Router);
      const resetSpy = jest.spyOn(TestBed.inject(ChurchService), 'resetBrowse');
      jest.spyOn(router, 'url', 'get').mockReturnValue('/churches?city=Austin');

      fixture.componentInstance.browseClick();

      expect(resetSpy).toHaveBeenCalledTimes(1);
    });
  });
});
