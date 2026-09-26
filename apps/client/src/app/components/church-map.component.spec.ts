import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChurchMapComponent } from './church-map.component';

/** Covers the graceful-degradation matrix: no key / no location / both present —
 *  see the component's own doc comment for why the directions link needs no key. */
describe('ChurchMapComponent', () => {
  function setup(inputs: Partial<{
    name: string; address: string; city: string; state: string; zipCode: string;
    latitude: number; longitude: number;
  }> = {}) {
    TestBed.configureTestingModule({
      imports: [ChurchMapComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ChurchMapComponent);
    fixture.componentRef.setInput('name', inputs.name ?? 'Test Church');
    if (inputs.address !== undefined) fixture.componentRef.setInput('address', inputs.address);
    if (inputs.city !== undefined) fixture.componentRef.setInput('city', inputs.city);
    if (inputs.state !== undefined) fixture.componentRef.setInput('state', inputs.state);
    if (inputs.zipCode !== undefined) fixture.componentRef.setInput('zipCode', inputs.zipCode);
    if (inputs.latitude !== undefined) fixture.componentRef.setInput('latitude', inputs.latitude);
    if (inputs.longitude !== undefined) fixture.componentRef.setInput('longitude', inputs.longitude);
    fixture.detectChanges();
    return { http, fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('renders nothing when there is neither an address nor coordinates', async () => {
    const { http, fixture, el } = setup();
    await fixture.whenStable();
    http.expectOne('/api/config').flush({ googleMapsEmbedKey: 'a-key' });
    await fixture.whenStable();

    expect(el.querySelector('iframe')).toBeNull();
    expect(el.textContent?.trim()).toBe('');
    http.verify();
  });

  it('shows only the directions link (no iframe) when no embed key is configured', async () => {
    const { http, fixture, el } = setup({ city: 'Austin', state: 'TX' });
    await fixture.whenStable();
    http.expectOne('/api/config').flush({ googleMapsEmbedKey: null });
    await fixture.whenStable();

    expect(el.querySelector('iframe')).toBeNull();
    const link = el.querySelector('a') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(link.href).toContain('maps/dir');
    expect(link.href).toContain('Austin');
  });

  it('renders the embed iframe using precise coordinates when both an address and coordinates are present', async () => {
    const { http, fixture, el } = setup({
      address: '123 Main St', city: 'Austin', state: 'TX', latitude: 30.27, longitude: -97.74,
    });
    await fixture.whenStable();
    http.expectOne('/api/config').flush({ googleMapsEmbedKey: 'embed-key-xyz' });
    await fixture.whenStable();

    const iframe = el.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).toBeTruthy();
    // Prefers lat/lng over the address string once both are available.
    expect(iframe.src).toContain('30.27%2C-97.74');
    expect(iframe.src).toContain('embed-key-xyz');
    expect(el.querySelector('a')?.getAttribute('href')).toContain('maps/dir');
  });

  it('falls back to the address string when coordinates are missing', async () => {
    const { http, fixture, el } = setup({ address: '123 Main St', city: 'Austin', state: 'TX' });
    await fixture.whenStable();
    http.expectOne('/api/config').flush({ googleMapsEmbedKey: 'embed-key-xyz' });
    await fixture.whenStable();

    const iframe = el.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe.src).toContain(encodeURIComponent('123 Main St'));
  });
});
