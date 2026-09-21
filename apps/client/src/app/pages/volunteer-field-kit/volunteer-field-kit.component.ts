import { Component, signal } from '@angular/core';
import * as QRCode from 'qrcode';

/**
 * The tabling/volunteer field kit (Prompt 15): a printable flyer with a QR code that
 * scans to /go, which resolves the nearest church via GET /api/churches/nearest
 * (Prompt 14) and forwards there. Printable via CSS @media print rather than a
 * generated PDF — no extra server round-trip or PDF library needed, and it prints
 * identically to what's shown on screen.
 */
@Component({
  selector: 'app-volunteer-field-kit',
  standalone: true,
  templateUrl: './volunteer-field-kit.component.html',
})
export class VolunteerFieldKitComponent {
  qrDataUrl = signal('');
  shortLink = signal('');
  error = signal('');

  constructor() {
    const link = `${window.location.origin}/go`;
    this.shortLink.set(link);

    QRCode.toDataURL(link, { margin: 1, width: 320 })
      .then((dataUrl) => this.qrDataUrl.set(dataUrl))
      .catch(() => this.error.set('Could not generate the QR code.'));
  }

  print() {
    window.print();
  }
}
