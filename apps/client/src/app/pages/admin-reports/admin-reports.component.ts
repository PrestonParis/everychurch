import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Report, ReportStatus } from '../../models/report.model';
import { ReportService } from '../../services/report.service';

@Component({
  selector: 'app-admin-reports',
  standalone: true,
  imports: [RouterModule, DatePipe],
  templateUrl: './admin-reports.component.html',
})
export class AdminReportsComponent {
  private reportService = inject(ReportService);

  reports = signal<Report[]>([]);
  loading = signal(true);
  error = signal('');
  statusFilter = signal<ReportStatus>('OPEN');
  decidingId = signal<number | null>(null);

  readonly STATUSES: ReportStatus[] = ['OPEN', 'ACTIONED', 'DISMISSED'];

  constructor() {
    this.refresh();
  }

  setFilter(status: ReportStatus) {
    this.statusFilter.set(status);
    this.refresh();
  }

  refresh() {
    this.loading.set(true);
    this.error.set('');
    this.reportService.list(this.statusFilter()).subscribe({
      next: (reports) => {
        this.reports.set(reports);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.error ?? 'Could not load reports — are you signed in as a Platform Admin?');
        this.loading.set(false);
      },
    });
  }

  action(id: number) {
    this.resolve(id, 'ACTIONED');
  }

  dismiss(id: number) {
    this.resolve(id, 'DISMISSED');
  }

  private resolve(id: number, status: 'ACTIONED' | 'DISMISSED') {
    this.decidingId.set(id);
    this.reportService.resolve(id, status).subscribe({
      next: () => {
        this.decidingId.set(null);
        this.refresh();
      },
      error: (err) => {
        this.decidingId.set(null);
        this.error.set(err.error?.error ?? 'Could not update that report.');
      },
    });
  }

  /** So the review link goes somewhere useful without a dedicated admin user-profile
   *  page existing yet. */
  targetLink(report: Report): string[] {
    return report.targetType === 'CHURCH' ? ['/churches', String(report.targetId)] : ['/messages', String(report.targetId)];
  }
}
