import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { FavoriteService } from '../services/favorite.service';

/**
 * A heart toggle, reusable both inline on the church profile page and as an overlay
 * on a directory card. Always a <button>, never an <a> — the card usage nests this
 * inside the card's own routerLink anchor, and a nested <a> would be invalid HTML.
 *
 * Deliberately not styled to match either of the app's two currently-coexisting
 * palettes (the original slate/indigo pages vs. the in-progress oxblood/gold
 * redesign — see docs/PROMPTS.md) — red-for-favorited/gray-for-not is close enough
 * to a universal convention that it reads fine against either, without needing a
 * variant input just to pick an accent color.
 */
@Component({
  selector: 'app-favorite-button',
  standalone: true,
  host: { class: 'contents' },
  templateUrl: './favorite-button.component.html',
})
export class FavoriteButtonComponent implements OnInit {
  churchId = input.required<number>();
  /** Card usage sits on a colored/photo background — an icon-only heart with no
   *  backing chip can disappear into it, so the card gives itself a small circular
   *  backing via this flag. The profile-page usage (plain white card background)
   *  doesn't need one. */
  withBacking = input<boolean>(false);
  /** Skips the status fetch — the Favorites page already knows every card it renders
   *  is favorited, so asking the server again per-card would be a pointless N+1. */
  initiallyFavorited = input<boolean>(false);

  /** Fires whenever the server confirms a change, with the new state — e.g. the
   *  Favorites page uses this to drop a card the moment it's unfavorited, instead of
   *  waiting for a full reload. */
  favoritedChange = output<boolean>();

  private authService = inject(AuthService);
  private favoriteService = inject(FavoriteService);
  private router = inject(Router);

  private readonly _favorited = signal(false);
  private readonly _loading = signal(false);
  private readonly _loaded = signal(false);
  get favorited(): boolean { return this._favorited(); }
  get loading(): boolean { return this._loading(); }
  get loaded(): boolean { return this._loaded(); }

  ngOnInit() {
    if (this.initiallyFavorited()) {
      this._favorited.set(true);
      this._loaded.set(true);
      return;
    }
    if (!this.authService.isAuthenticated()) return;
    this.favoriteService.status(this.churchId()).subscribe({
      next: (status) => {
        this._favorited.set(status.favorited);
        this._loaded.set(true);
      },
      error: () => this._loaded.set(true),
    });
  }

  toggle(event: Event) {
    // Swallow the click before it reaches a parent routerLink (the card usage).
    event.preventDefault();
    event.stopPropagation();

    if (!this.authService.isAuthenticated()) {
      this.router.navigate(['/login']);
      return;
    }
    if (this.loading) return;

    const wasFavorited = this.favorited;
    this._loading.set(true);
    const obs = wasFavorited
      ? this.favoriteService.unfavorite(this.churchId())
      : this.favoriteService.favorite(this.churchId());

    obs.subscribe({
      // Optimistic-but-confirmed: the button only flips once the server agrees, so a
      // failed request (e.g. someone else's tab already unfavorited it) can't leave
      // the button showing a state the server doesn't actually have.
      next: () => {
        this._favorited.set(!wasFavorited);
        this._loading.set(false);
        this.favoritedChange.emit(!wasFavorited);
      },
      error: () => this._loading.set(false),
    });
  }
}
