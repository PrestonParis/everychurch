import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ChurchPermission, ChurchStaffSummary } from '../../models/church-staff.model';
import { ChurchStaffService } from '../../services/church-staff.service';
import { ChurchService } from '../../services/church.service';
import { Church } from '../../models/church.model';

/** Every permission that can be granted to a non-owner staff member, with the label
 *  shown in the UI. Mirrors ChurchPermission exactly (server/.../model/ChurchPermission.java) —
 *  add a new one there, add it here too. */
export const PERMISSION_LABELS: Record<ChurchPermission, string> = {
  EDIT_PROFILE: 'Edit church profile',
  MANAGE_SERMONS: 'Manage sermons',
  MANAGE_EVENTS: 'Manage events',
  MANAGE_STAFF: 'Manage staff',
  MANAGE_POSTS: 'Manage posts',
  MANAGE_BELIEFS: 'Manage beliefs page',
};
export const ALL_PERMISSIONS = Object.keys(PERMISSION_LABELS) as ChurchPermission[];

/**
 * The staff-management screen the API has had since Prompt 13 but never got a UI for
 * (found in review) — add/remove staff, grant/revoke individual permissions. Reached
 * from the church profile's management row, gated on MANAGE_STAFF there the same way
 * every other control is (church-profile.component.ts's canManageStaff).
 */
@Component({
  selector: 'app-church-staff',
  standalone: true,
  imports: [RouterModule, FormsModule],
  templateUrl: './church-staff.component.html',
})
export class ChurchStaffComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private churchStaffService = inject(ChurchStaffService);
  private churchService = inject(ChurchService);

  readonly ALL_PERMISSIONS = ALL_PERMISSIONS;
  readonly PERMISSION_LABELS = PERMISSION_LABELS;

  churchId!: number;
  church = signal<Church | null>(null);
  staff = signal<ChurchStaffSummary[]>([]);
  loading = signal(true);
  error = signal('');

  // Add-staff form
  newEmail = '';
  newPermissions = signal<Set<ChurchPermission>>(new Set());
  adding = signal(false);
  addError = signal('');

  // Per-row saving state, keyed by staff id — lets one row's PATCH be in flight
  // without disabling the whole table.
  savingId = signal<number | null>(null);
  removingId = signal<number | null>(null);

  ngOnInit() {
    this.churchId = Number(this.route.snapshot.paramMap.get('id'));
    this.churchService.getById(this.churchId).subscribe({ next: (c) => this.church.set(c) });
    this.load();
  }

  private load() {
    this.loading.set(true);
    this.churchStaffService.list(this.churchId).subscribe({
      next: (staff) => {
        this.staff.set(staff);
        this.loading.set(false);
        this.error.set('');
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err.error?.error ?? 'Could not load the staff list.');
      },
    });
  }

  toggleNewPermission(permission: ChurchPermission) {
    const set = new Set(this.newPermissions());
    set.has(permission) ? set.delete(permission) : set.add(permission);
    this.newPermissions.set(set);
  }

  addStaff() {
    const email = this.newEmail.trim();
    if (!email || this.adding()) return;

    this.adding.set(true);
    this.addError.set('');
    this.churchStaffService.add(this.churchId, email, [...this.newPermissions()]).subscribe({
      next: (added) => {
        this.staff.set([...this.staff(), added]);
        this.newEmail = '';
        this.newPermissions.set(new Set());
        this.adding.set(false);
      },
      error: (err) => {
        this.adding.set(false);
        this.addError.set(err.error?.error ?? 'Could not add that person.');
      },
    });
  }

  togglePermission(member: ChurchStaffSummary, permission: ChurchPermission) {
    if (member.isOwner || this.savingId() === member.id) return;

    const current = new Set(member.permissions);
    current.has(permission) ? current.delete(permission) : current.add(permission);

    this.savingId.set(member.id);
    this.churchStaffService.updatePermissions(this.churchId, member.id, [...current]).subscribe({
      next: (updated) => {
        this.staff.set(this.staff().map((s) => (s.id === member.id ? updated : s)));
        this.savingId.set(null);
      },
      error: () => this.savingId.set(null),
    });
  }

  removeStaff(member: ChurchStaffSummary) {
    if (member.isOwner) return;
    if (!confirm(`Remove ${member.userEmail} from this church's staff?`)) return;

    this.removingId.set(member.id);
    this.churchStaffService.remove(this.churchId, member.id).subscribe({
      next: () => {
        this.staff.set(this.staff().filter((s) => s.id !== member.id));
        this.removingId.set(null);
      },
      error: () => this.removingId.set(null),
    });
  }
}
