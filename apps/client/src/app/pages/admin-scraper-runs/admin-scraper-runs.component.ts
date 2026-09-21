import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ScraperRunService } from '../../services/scraper-run.service';
import { ScraperRun, US_STATE_CODES } from '../../models/scraper-run.model';

@Component({
  selector: 'app-admin-scraper-runs',
  standalone: true,
  imports: [RouterModule, FormsModule, DatePipe],
  templateUrl: './admin-scraper-runs.component.html',
})
export class AdminScraperRunsComponent {
  private scraperRunService = inject(ScraperRunService);

  readonly STATE_CODES = US_STATE_CODES;

  runs = signal<ScraperRun[]>([]);
  loading = signal(true);
  error = signal('');

  selectedRegion = ''; // '' = let the server auto-pick the next candidate
  triggering = signal(false);
  triggerMessage = signal('');

  constructor() {
    this.refresh();
  }

  refresh() {
    this.loading.set(true);
    this.scraperRunService.list().subscribe({
      next: (runs) => {
        this.runs.set(runs);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.error ?? 'Could not load scraper runs — are you signed in as a Platform Admin?');
        this.loading.set(false);
      },
    });
  }

  trigger() {
    this.triggering.set(true);
    this.triggerMessage.set('');
    this.error.set('');

    this.scraperRunService.trigger(this.selectedRegion || undefined).subscribe({
      next: (result) => {
        this.triggering.set(false);
        this.triggerMessage.set(result.message);
        this.refresh();
      },
      error: (err) => {
        this.triggering.set(false);
        this.error.set(err.error?.error ?? 'Could not trigger a scraper run.');
      },
    });
  }

  statusBadge(status: ScraperRun['status']): string {
    const map: Record<ScraperRun['status'], string> = {
      RUNNING: 'bg-amber-100 text-amber-700',
      COMPLETED: 'bg-emerald-100 text-emerald-700',
      FAILED: 'bg-red-100 text-red-700',
    };
    return map[status];
  }
}
