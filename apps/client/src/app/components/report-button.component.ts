import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ReportTargetType } from '../models/report.model';
import { ReportService } from '../services/report.service';
import { AuthService } from '../services/auth.service';
import { Router } from '@angular/router';

/** A "Report" link that expands into a short reason form — reusable anywhere a user
 *  or a church needs to be reportable (currently: the DM thread header). Any
 *  authenticated user can file one; a Platform Admin reviews it later
 *  (AdminReportsComponent). */
@Component({
  selector: 'app-report-button',
  standalone: true,
  imports: [FormsModule],
  host: { class: 'contents' },
  templateUrl: './report-button.component.html',
})
export class ReportButtonComponent {
  targetType = input.required<ReportTargetType>();
  targetId = input.required<number>();

  private reportService = inject(ReportService);
  private authService = inject(AuthService);
  private router = inject(Router);

  open = signal(false);
  reason = '';
  submitting = signal(false);
  submitted = signal(false);
  error = signal('');

  toggle() {
    if (!this.authService.isAuthenticated()) {
      this.router.navigate(['/login']);
      return;
    }
    this.open.update((v) => !v);
  }

  submit() {
    const trimmed = this.reason.trim();
    if (!trimmed || this.submitting()) return;

    this.submitting.set(true);
    this.error.set('');
    this.reportService.create(this.targetType(), this.targetId(), trimmed).subscribe({
      next: () => {
        this.submitting.set(false);
        this.submitted.set(true);
        this.open.set(false);
      },
      error: (err) => {
        this.submitting.set(false);
        this.error.set(err.error?.error ?? 'Could not submit the report. Please try again.');
      },
    });
  }
}
