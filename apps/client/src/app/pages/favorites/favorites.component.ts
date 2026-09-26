import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Church } from '../../models/church.model';
import { FavoriteService } from '../../services/favorite.service';
import { FavoriteButtonComponent } from '../../components/favorite-button.component';

@Component({
  selector: 'app-favorites',
  standalone: true,
  imports: [RouterModule, FavoriteButtonComponent],
  templateUrl: './favorites.component.html',
})
export class FavoritesComponent implements OnInit {
  private favoriteService = inject(FavoriteService);

  churches = signal<Church[]>([]);
  loading = signal(true);
  error = signal(false);

  // Only shown once the favorites list is actually empty — this is the empty
  // state's content, not something shown alongside a populated grid.
  recentlyViewed = signal<Church[]>([]);
  suggestions = signal<Church[]>([]);
  loadingSuggestions = signal(false);

  ngOnInit() {
    this.load();
  }

  private load() {
    this.favoriteService.myFavorites().subscribe({
      next: (churches) => {
        this.churches.set(churches);
        this.loading.set(false);
        if (churches.length === 0) this.loadEmptyStateContent();
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  private loadEmptyStateContent() {
    this.loadingSuggestions.set(true);
    this.favoriteService.recentlyViewed().subscribe({
      next: (churches) => this.recentlyViewed.set(churches),
      error: () => this.recentlyViewed.set([]),
    });
    this.favoriteService.suggestions().subscribe({
      next: (churches) => {
        this.suggestions.set(churches);
        this.loadingSuggestions.set(false);
      },
      error: () => this.loadingSuggestions.set(false),
    });
  }

  /** The favorite-button overlay favorites/unfavorites in place, on any of the three
   *  grids this page can show — simplest to just re-fetch everything on any change
   *  (favorites is small; this isn't a hot path) than to hand-patch three lists that
   *  can affect each other (favoriting a suggestion means it's not a suggestion
   *  anymore AND the page should flip out of the empty state). */
  onFavoritedChange() {
    this.load();
  }
}
