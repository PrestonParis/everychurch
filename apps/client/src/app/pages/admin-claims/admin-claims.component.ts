import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Claim, ClaimStatus } from '../../models/claim.model';
import { ClaimService } from '../../services/claim.service';

@Component({
  selector: 'app-admin-claims',
  standalone: true,
  imports: [RouterModule, DatePipe],
  templateUrl: './admin-claims.component.html',
})
export class AdminClaimsComponent {
  private claimService = inject(ClaimService);

  claims = signal<Claim[]>([]);
  loading = signal(true);
  error = signal('');
  statusFilter = signal<ClaimStatus>('PENDING');
  decidingId = signal<number | null>(null);

  readonly STATUSES: ClaimStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'RELEASED'];

  constructor() {
    this.refresh();
  }

  setFilter(status: ClaimStatus) {
    this.statusFilter.set(status);
    this.refresh();
  }

  refresh() {
    this.loading.set(true);
    this.error.set('');
    this.claimService.list(this.statusFilter()).subscribe({
      next: (claims) => {
        this.claims.set(claims);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.error ?? 'Could not load claims — are you signed in as a Platform Admin?');
        this.loading.set(false);
      },
    });
  }

  approve(id: number) {
    this.decide(id, this.claimService.approve(id), 'Could not approve that claim.');
  }

  reject(id: number) {
    this.decide(id, this.claimService.reject(id), 'Could not reject that claim.');
  }

  private decide(id: number, request: ReturnType<ClaimService['approve']>, failure: string) {
    this.decidingId.set(id);
    request.subscribe({
      next: () => {
        this.decidingId.set(null);
        this.refresh();
      },
      error: (err) => {
        this.decidingId.set(null);
        this.error.set(err.error?.error ?? failure);
      },
    });
  }
}
