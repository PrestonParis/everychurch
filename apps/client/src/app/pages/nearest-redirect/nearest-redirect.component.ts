import { Component, signal } from '@angular/core';
import { RouterModule } from '@angular/router';

/**
 * The volunteer field kit's QR code (Prompt 15) points here. This page has no
 * server-side counterpart — it just reads the *scanning device's* location and hands
 * it to the server's public GET /r/nearest, which 302s to the nearest church's
 * profile. No login required.
 */
@Component({
  selector: 'app-nearest-redirect',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './nearest-redirect.component.html',
})
export class NearestRedirectComponent {
  status = signal<'locating' | 'error'>('locating');
  errorMessage = signal('');

  constructor() {
    this.locate();
  }

  locate() {
    this.status.set('locating');

    if (!('geolocation' in navigator)) {
      this.status.set('error');
      this.errorMessage.set('This browser does not support location services.');
      return;
    }

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const { latitude, longitude } = position.coords;
        window.location.href = `/r/nearest?lat=${latitude}&lng=${longitude}`;
      },
      () => {
        this.status.set('error');
        this.errorMessage.set('Location access was denied. Enable location for this site and try again.');
      },
      { timeout: 10_000 }
    );
  }
}
