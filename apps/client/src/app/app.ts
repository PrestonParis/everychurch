import { Component, inject, signal } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from './services/auth.service';

@Component({
  imports: [RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private router = inject(Router);
  protected authService = inject(AuthService);

  /** The Admin menu opens on click (not hover) so it works by touch and keyboard, at every width. */
  adminOpen = signal(false);

  toggleAdmin() {
    this.adminOpen.set(!this.adminOpen());
  }

  closeAdmin() {
    this.adminOpen.set(false);
  }

  logout() {
    this.closeAdmin();
    this.authService.logout();
    this.router.navigate(['/churches']);
  }
}
