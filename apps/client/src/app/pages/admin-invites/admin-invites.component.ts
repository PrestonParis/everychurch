import { Component, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { AdminService } from '../../services/admin.service';
import { VolunteerInvite } from '../../models/volunteer-invite.model';

@Component({
  selector: 'app-admin-invites',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './admin-invites.component.html',
})
export class AdminInvitesComponent {
  private adminService = inject(AdminService);

  invites = signal<VolunteerInvite[]>([]);
  loading = signal(true);
  error = signal('');
  generating = signal(false);

  constructor() {
    this.refresh();
  }

  refresh() {
    this.loading.set(true);
    this.adminService.listInvites().subscribe({
      next: (invites) => {
        this.invites.set(invites);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.error ?? 'Could not load invites — are you signed in as a Platform Admin?');
        this.loading.set(false);
      },
    });
  }

  generate() {
    this.generating.set(true);
    this.adminService.generateInvite().subscribe({
      next: () => {
        this.generating.set(false);
        this.refresh();
      },
      error: (err) => {
        this.generating.set(false);
        this.error.set(err.error?.error ?? 'Could not generate an invite.');
      },
    });
  }

  revoke(id: number) {
    this.adminService.revokeInvite(id).subscribe({
      next: () => this.refresh(),
      error: (err) => this.error.set(err.error?.error ?? 'Could not revoke that invite.'),
    });
  }
}
