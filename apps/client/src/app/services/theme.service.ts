import { Injectable, signal } from '@angular/core';

export type ThemePreference = 'system' | 'light' | 'dark';

// Also read by the inline script in index.html, which applies the saved theme
// before Angular boots so a dark-mode user doesn't see a flash of the light page.
const STORAGE_KEY = 'ec_theme';

/**
 * Light/dark override for the color tokens in styles.scss. 'system' leaves
 * <html> without a data-theme attribute, so the prefers-color-scheme media query
 * decides (and keeps tracking OS changes live); 'light'/'dark' set the attribute,
 * which the stylesheet checks ahead of the media query. Stored per-device in
 * localStorage rather than on the profile — it's a display setting, and signed-out
 * visitors can use it too.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private pref = signal<ThemePreference>(this.readStored());

  readonly preference = this.pref.asReadonly();

  constructor() {
    this.apply(this.pref());
  }

  setPreference(pref: ThemePreference): void {
    this.pref.set(pref);
    this.apply(pref);
    try {
      if (pref === 'system') localStorage.removeItem(STORAGE_KEY);
      else localStorage.setItem(STORAGE_KEY, pref);
    } catch {
      // localStorage unavailable (private browsing, etc.) — applies for this session only
    }
  }

  private apply(pref: ThemePreference): void {
    const root = document.documentElement;
    if (pref === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', pref);
  }

  private readStored(): ThemePreference {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      return stored === 'light' || stored === 'dark' ? stored : 'system';
    } catch {
      return 'system';
    }
  }
}
