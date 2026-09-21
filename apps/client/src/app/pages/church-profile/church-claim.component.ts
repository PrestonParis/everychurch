import { Component, input, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ClaimService } from '../../services/claim.service';

/**
 * "Is this your church?" — how a listing gets an owner. Any logged-in user can ask; a
 * Platform Admin reviews the request, and only on approval do they become the church's
 * owner (and gain the edit/photos/sermons/events controls). Renders nothing once a church
 * has been claimed.
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

  private claimService = inject(ClaimService);
  authService = inject(AuthService);

  notes = '';
  open = signal(false);
  submitting = signal(false);
  submitted = signal(false);
  error = signal('');

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
        this.error.set(err.error?.error ?? 'Could not send your request. Please try again.');
      },
    });
  }
}
