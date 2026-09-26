import { Component, OnInit, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { FollowService } from '../services/follow.service';

/** "Follow this church" — the social-feed relationship (see Follow's class comment
 *  server-side). A text button, not an icon like FavoriteButtonComponent: "Follow" is
 *  a strong enough social-media convention that spelling it out reads better than a
 *  bare icon would for an action this central to the feature. */
@Component({
  selector: 'app-follow-button',
  standalone: true,
  host: { class: 'contents' },
  templateUrl: './follow-button.component.html',
})
export class FollowButtonComponent implements OnInit {
  churchId = input.required<number>();

  private authService = inject(AuthService);
  private followService = inject(FollowService);
  private router = inject(Router);

  private readonly _following = signal(false);
  private readonly _followerCount = signal(0);
  private readonly _loading = signal(false);
  private readonly _loaded = signal(false);
  get following(): boolean { return this._following(); }
  get followerCount(): number { return this._followerCount(); }
  get loading(): boolean { return this._loading(); }
  get loaded(): boolean { return this._loaded(); }

  ngOnInit() {
    if (!this.authService.isAuthenticated()) return;
    this.followService.status(this.churchId()).subscribe({
      next: (status) => {
        this._following.set(status.following);
        this._followerCount.set(status.followerCount);
        this._loaded.set(true);
      },
      error: () => this._loaded.set(true),
    });
  }

  toggle() {
    if (!this.authService.isAuthenticated()) {
      this.router.navigate(['/login']);
      return;
    }
    if (this.loading) return;

    const wasFollowing = this.following;
    this._loading.set(true);
    const obs = wasFollowing
      ? this.followService.unfollow(this.churchId())
      : this.followService.follow(this.churchId());

    obs.subscribe({
      next: () => {
        this._following.set(!wasFollowing);
        this._followerCount.update((n) => n + (wasFollowing ? -1 : 1));
        this._loading.set(false);
      },
      error: () => this._loading.set(false),
    });
  }
}
