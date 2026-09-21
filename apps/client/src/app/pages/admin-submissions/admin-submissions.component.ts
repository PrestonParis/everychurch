import { Component, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { ChurchSubmissionService } from '../../services/church-submission.service';
import { ChurchSubmission, ChurchSubmissionStatus } from '../../models/church-submission.model';

@Component({
  selector: 'app-admin-submissions',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './admin-submissions.component.html',
})
export class AdminSubmissionsComponent {
  private submissionService = inject(ChurchSubmissionService);

  submissions = signal<ChurchSubmission[]>([]);
  loading = signal(true);
  error = signal('');
  statusFilter = signal<ChurchSubmissionStatus>('PENDING');
  decidingId = signal<number | null>(null);

  readonly STATUSES: ChurchSubmissionStatus[] = ['PENDING', 'APPROVED', 'REJECTED'];

  constructor() {
    this.refresh();
  }

  setFilter(status: ChurchSubmissionStatus) {
    this.statusFilter.set(status);
    this.refresh();
  }

  refresh() {
    this.loading.set(true);
    this.submissionService.list(this.statusFilter()).subscribe({
      next: (submissions) => {
        this.submissions.set(submissions);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.error ?? 'Could not load submissions — are you signed in as a Platform Admin?');
        this.loading.set(false);
      },
    });
  }

  approve(id: number) {
    this.decidingId.set(id);
    this.submissionService.approve(id).subscribe({
      next: () => {
        this.decidingId.set(null);
        this.refresh();
      },
      error: (err) => {
        this.decidingId.set(null);
        this.error.set(err.error?.error ?? 'Could not approve that submission.');
      },
    });
  }

  reject(id: number) {
    this.decidingId.set(id);
    this.submissionService.reject(id).subscribe({
      next: () => {
        this.decidingId.set(null);
        this.refresh();
      },
      error: (err) => {
        this.decidingId.set(null);
        this.error.set(err.error?.error ?? 'Could not reject that submission.');
      },
    });
  }

  isNewChurch(submission: ChurchSubmission): boolean {
    return !submission.targetChurchId;
  }

  submittedByLabel(submission: ChurchSubmission): string {
    return submission.submittedBy === 'scraper' ? 'submitted by the scraper' : `submitted by user #${submission.submittedBy}`;
  }

  proposedEntries(submission: ChurchSubmission): [string, unknown][] {
    return Object.entries(submission.proposedChanges).filter(
      ([key, value]) => value !== null && value !== undefined && key !== 'serviceTimes'
    );
  }
}
