import { Component, inject, signal } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import { UserAvatarComponent } from './components/user-avatar.component';
import { AuthService } from './services/auth.service';
import { ChurchService } from './services/church.service';

@Component({
  imports: [RouterModule, UserAvatarComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private router = inject(Router);
  protected authService = inject(AuthService);
  private churchService = inject(ChurchService);

  /** The Admin menu opens on click (not hover) so it works by touch and keyboard, at every width. */
  adminOpen = signal(false);

  toggleAdmin() {
    this.adminOpen.set(!this.adminOpen());
  }

  closeAdmin() {
    this.adminOpen.set(false);
  }

  /** Same click-to-open pattern as the Admin menu above. */
  accountMenuOpen = signal(false);

  toggleAccountMenu() {
    this.accountMenuOpen.set(!this.accountMenuOpen());
  }

  closeAccountMenu() {
    this.accountMenuOpen.set(false);
  }

  /** Browse/Events/Feed/Favorites/Messages are `hidden sm:block` in the nav bar
   *  itself — this is where they reappear below `sm`, rather than just vanishing
   *  with no way to reach them on a phone. */
  mobileMenuOpen = signal(false);

  toggleMobileMenu() {
    this.mobileMenuOpen.set(!this.mobileMenuOpen());
  }

  closeMobileMenu() {
    this.mobileMenuOpen.set(false);
  }

  /** Browse's routerLink target (/churches) is also this app's default route, so
   *  clicking it while already there is a same-URL navigation the Router ignores
   *  outright — no navigation event fires, so any active search/filters on the
   *  page stay exactly as they were and the click looks like it did nothing. Ask
   *  the church list to reset itself directly in that case instead. */
  browseClick() {
    if (this.router.url.split('?')[0] === '/churches') this.churchService.resetBrowse();
  }

  logout() {
    this.closeAdmin();
    this.closeAccountMenu();
    this.closeMobileMenu();
    this.authService.logout();
    this.router.navigate(['/churches']);
  }
}
