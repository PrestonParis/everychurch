import { Component, computed, input } from '@angular/core';

/**
 * Shows the user's avatar image, or a fallback circle with their initial (in the
 * site's oxblood/gold palette) when they have none — used by both the nav and the
 * account page so this fallback logic isn't duplicated between them.
 */
@Component({
  selector: 'app-user-avatar',
  standalone: true,
  host: { class: 'contents' },
  templateUrl: './user-avatar.component.html',
})
export class UserAvatarComponent {
  avatarUrl = input<string | undefined>(undefined);
  displayName = input.required<string>();
  /** 'sm' for the nav trigger, 'lg' for the account page's own avatar. */
  size = input<'sm' | 'lg'>('sm');

  sizeClasses = computed(() => (this.size() === 'lg' ? 'w-20 h-20 text-2xl' : 'w-8 h-8 text-xs'));

  initial = computed(() => this.displayName().trim().charAt(0).toUpperCase() || '?');
}
