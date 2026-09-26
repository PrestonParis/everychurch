import { Component, OnInit, inject, input, computed, signal } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ConfigService } from '../services/config.service';

/**
 * A location card for the church profile page: an embedded Google map (when the
 * server has a Maps Embed key configured) plus a "Get Directions" link that always
 * works, key or not — the directions link is a plain maps.google.com URL, not a
 * Maps Platform API call, so it needs no key at all. This means the card is still
 * useful (directions) even in an environment that's skipped Maps Platform entirely,
 * matching the app's established graceful-degradation pattern (S3, geocoding, mail).
 *
 * Renders nothing (host: 'contents') if there's no address AND no coordinates —
 * an unclaimed, freshly-scraped listing sometimes has neither.
 */
@Component({
  selector: 'app-church-map',
  standalone: true,
  host: { class: 'contents' },
  templateUrl: './church-map.component.html',
})
export class ChurchMapComponent implements OnInit {
  name = input.required<string>();
  address = input<string | undefined>();
  city = input<string | undefined>();
  state = input<string | undefined>();
  zipCode = input<string | undefined>();
  latitude = input<number | undefined>();
  longitude = input<number | undefined>();

  private sanitizer = inject(DomSanitizer);
  private configService = inject(ConfigService);

  private readonly _embedKey = signal<string | null>(null);

  ngOnInit() {
    this.configService.get().subscribe({
      next: (config) => this._embedKey.set(config.googleMapsEmbedKey),
      // No config = no embed, same as no key — the directions link still renders.
      error: () => this._embedKey.set(null),
    });
  }

  /** Prefers precise coordinates (always present once geocoded); falls back to the
   *  formatted address string for a church saved before geocoding was configured. */
  private readonly locationQuery = computed(() => {
    const lat = this.latitude();
    const lng = this.longitude();
    if (lat != null && lng != null) return `${lat},${lng}`;

    const parts = [this.address(), this.city(), this.state(), this.zipCode()].filter((p) => !!p);
    return parts.length > 0 ? parts.join(', ') : null;
  });

  get hasLocation(): boolean {
    return this.locationQuery() !== null;
  }

  get embedUrl(): SafeResourceUrl | null {
    const key = this._embedKey();
    const query = this.locationQuery();
    if (!key || !query) return null;

    const url = `https://www.google.com/maps/embed/v1/place?key=${encodeURIComponent(key)}&q=${encodeURIComponent(
      `${this.name()}, ${query}`
    )}`;
    return this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }

  get directionsUrl(): string | null {
    const query = this.locationQuery();
    if (!query) return null;
    return `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(query)}`;
  }
}
