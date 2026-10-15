import { Component, input, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ClaimService } from '../../services/claim.service';

/**
 * "Is this your church?" — how a listing gets an owner. Any logged-in user can ask; a
 * Platform Admin reviews the request, and only on approval do they become the church's
 * owner (and gain the edit/photos/sermons/events controls). Once a church is claimed, its
 * owner or a Platform Admin sees an "Unclaim" control here instead; everyone else sees
 * nothing.
 */
@Component({
  selector: 'app-church-claim',
  standalone: true,
  imports: [FormsModule, RouterModule],
  host: { class: 'block' },
  templateUrl: './church-claim.component.html',
})
export class ChurchClaimComponent {
  churchId = input.required<number>();
  claimed = input<boolean>(false);
  /** The viewer owns this church or is a Platform Admin — see ClaimService.release server-side. */
  canRelease = input<boolean>(false);
  released = output<void>();

  private claimService = inject(ClaimService);
  authService = inject(AuthService);

  notes = '';
  open = signal(false);
  submitting = signal(false);
  submitted = signal(false);
  error = signal('');
  confirmingRelease = signal(false);
  releasing = signal(false);

  submit() {
    this.submitting.set(true);
    this.error.set('');
    this.claimService.request(this.churchId(), this.notes).subscribe({
      next: () => {
        this.submitting.set(false);
        this.submitted.set(true);
      },
      error: (err) => {
        this.submitting.set(false);
        // On a 401 the auth interceptor has already logged the user out, so the card
        // re-renders with its "Log in to claim" prompt — no error text needed.
        if (err.status === 401) return;
        this.error.set(err.error?.error ?? 'Could not send your request. Please try again.');
      },
    });
  }

  release() {
    this.releasing.set(true);
    this.error.set('');
    this.claimService.release(this.churchId()).subscribe({
      next: () => {
        this.releasing.set(false);
        this.confirmingRelease.set(false);
        this.released.emit();
      },
      error: (err) => {
        this.releasing.set(false);
        if (err.status === 401) return;
        this.error.set(err.error?.error ?? 'Could not unclaim this church. Please try again.');
      },
    });
  }
}
