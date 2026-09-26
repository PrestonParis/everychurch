import { DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import {
  FaithCatalog,
  FaithDimension,
  FaithEditor,
  FaithProfile,
  FaithProfileInput,
  churchOptions,
} from '../../models/faith.model';
import { FaithService, faithError } from '../../services/faith.service';
import { ChurchStaffService } from '../../services/church-staff.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-church-beliefs',
  standalone: true,
  imports: [FormsModule, RouterModule],
  templateUrl: './beliefs.component.html',
  styleUrl: './faith.scss',
})
export class BeliefsComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  private route = inject(ActivatedRoute);
  private service = inject(FaithService);
  private staff = inject(ChurchStaffService);
  auth = inject(AuthService);
  id = Number(this.route.snapshot.paramMap.get('id'));
  editing = this.route.snapshot.data['editing'] === true;
  catalog = signal<FaithCatalog | null>(null);
  profile = signal<FaithProfile | null>(null);
  editor = signal<FaithEditor | null>(null);
  canEdit = signal(false);
  error = signal('');
  notice = signal('');
  busy = signal(false);
  preview = signal(false);
  confirmed = false;
  input: FaithProfileInput = {
    catalogVersion: '',
    traditionId: null,
    answers: {},
    participation: '',
    baseRevision: null,
  };
  options = churchOptions;
  ngOnInit() {
    if (this.editing) {
      this.service
        .editor(this.id)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (e) => {
            this.editor.set(e);
            this.profile.set(e.published);
            this.canEdit.set(true);
            this.loadCatalog(e.draft.content?.catalogVersion, () => {
              if (e.draft.content)
                this.input = structuredClone(e.draft.content);
            });
          },
          error: (e) => this.error.set(faithError(e)),
        });
    } else {
      this.service
        .profile(this.id)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (p) => {
            this.profile.set(p);
            this.loadCatalog(p.content?.catalogVersion);
          },
          error: (e) => this.error.set(faithError(e)),
        });
      if (this.auth.currentUser()?.role === 'PLATFORM_ADMIN')
        this.canEdit.set(true);
      else if (this.auth.isAuthenticated())
        this.staff
          .myStaffChurches()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: (rows) =>
              this.canEdit.set(
                rows.some(
                  (s) =>
                    s.churchId === this.id &&
                    (s.isOwner || s.permissions.includes('MANAGE_BELIEFS')),
                ),
              ),
            error: () => {},
          });
    }
  }
  loadCatalog(version?: string, after?: () => void) {
    this.service
      .catalog(version)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (c) => {
          this.catalog.set(c);
          this.input.catalogVersion = c.version;
          after?.();
        },
        error: (e) => this.error.set(faithError(e)),
      });
  }
  value(id: string) {
    return this.input.answers[id]?.option ?? 'unknown';
  }
  note(id: string) {
    return this.input.answers[id]?.note ?? '';
  }
  source(id: string) {
    return this.input.answers[id]?.sourceUrl ?? '';
  }
  change(id: string, field: 'option' | 'note' | 'sourceUrl', value: string) {
    this.input.answers[id] = {
      ...this.input.answers[id],
      option: this.value(id),
      [field]: value,
    };
    this.confirmed = false;
  }
  latestQuestions() {
    this.service
      .catalog()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (catalog) => {
          this.catalog.set(catalog);
          this.input.catalogVersion = catalog.version;
          if (!catalog.traditions.some((t) => t.id === this.input.traditionId))
            this.input.traditionId = null;
          this.confirmed = false;
          this.notice.set(
            'Loaded the latest questions. Existing answers were retained; review any new topics before publishing.',
          );
        },
        error: (e) => this.error.set(faithError(e)),
      });
  }
  template() {
    const t = this.catalog()?.traditions.find(
      (t) => t.id === this.input.traditionId,
    );
    if (!t) return;
    for (const [id, option] of Object.entries(t.answers))
      if (
        !this.input.answers[id] ||
        this.input.answers[id].option === 'unknown'
      )
        this.input.answers[id] = {
          option,
          sourceUrl: t.sources[0].url,
          note: 'Review this reference position before publishing.',
        };
    this.confirmed = false;
    this.notice.set(
      'Reference answers filled empty topics only. Review every answer: nothing is public until you confirm and publish.',
    );
  }
  label(d: FaithDimension, profile: FaithProfileInput | null | undefined) {
    const value = profile?.answers[d.id]?.option;
    return this.options(d).find((o) => o.id === value)?.label ?? 'Not provided';
  }
  reference(d: FaithDimension) {
    const t = this.catalog()?.traditions.find(
      (t) => t.id === this.input.traditionId,
    );
    const value = t?.answers[d.id];
    return value ? d.options.find((o) => o.id === value)?.label : null;
  }
  differs(d: FaithDimension) {
    const t = this.catalog()?.traditions.find(
      (t) => t.id === this.input.traditionId,
    );
    return (
      !!t?.answers[d.id] &&
      this.value(d.id) !== 'unknown' &&
      t.answers[d.id] !== this.value(d.id)
    );
  }
  save(publish: boolean) {
    if (publish && !this.confirmed) {
      this.error.set(
        'Confirm that you reviewed these statements before publishing.',
      );
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    const call = publish
      ? this.service.publish(this.id, this.input)
      : this.service.draft(this.id, this.input);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (p) => {
        this.busy.set(false);
        this.notice.set(
          publish
            ? 'Your church’s confirmed beliefs are now published and available for matching.'
            : 'Draft saved. Your public profile has not changed.',
        );
        if (publish) {
          this.profile.set(p);
          this.input.baseRevision = p.revision;
          this.confirmed = false;
          this.service
            .editor(this.id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: (e) => this.editor.set(e) });
        }
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(faithError(e));
      },
    });
  }
}
