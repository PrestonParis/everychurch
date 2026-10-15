import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormArray, FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { Church, DENOMINATIONS, ServiceTime } from '../../models/church.model';
import { ChurchService } from '../../services/church.service';

@Component({
  selector: 'app-church-form',
  standalone: true,
  imports: [RouterModule, ReactiveFormsModule, FormsModule],
  templateUrl: './church-form.component.html',
})
export class ChurchFormComponent implements OnInit {
  private fb = inject(FormBuilder);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private churchService = inject(ChurchService);

  form = this.fb.group({
    name: ['', Validators.required],
    denomination: [''],
    description: [''],
    address: [''],
    city: [''],
    state: [''],
    zipCode: [''],
    phone: [''],
    email: [''],
    website: [''],
    imageUrl: [''],
    pastor: [''],
    facebook: [''],
    instagram: [''],
    youtube: [''],
    serviceTimes: this.fb.array([]),
  });

  importUrl = '';

  isEdit = false;
  churchId: number | null = null;

  // Async-mutated view state is signal-backed (exposed as plain-looking properties so the
  // template is unchanged). The app is zoneless, so a plain field assigned inside an HTTP
  // callback never repaints: editing a church would spin forever and a failed save would
  // leave the button stuck on "saving".
  private readonly _importing = signal(false);
  private readonly _importError = signal('');
  private readonly _importSuccess = signal(false);
  private readonly _loading = signal(false);
  private readonly _saving = signal(false);
  private readonly _saveError = signal('');
  get importing(): boolean { return this._importing(); }
  get importError(): string { return this._importError(); }
  get importSuccess(): boolean { return this._importSuccess(); }
  get loading(): boolean { return this._loading(); }
  get saving(): boolean { return this._saving(); }
  get saveError(): string { return this._saveError(); }

  readonly DENOMINATIONS = DENOMINATIONS;

  readonly DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

  readonly SERVICE_TYPES = [
    'Sunday Service',
    'Mass',
    'Bible Study',
    'Youth Group',
    'Choir / Worship Practice',
    'Prayer Meeting',
    'Spanish Service',
    'Evening Service',
    'Other',
  ];

  get serviceTimesArray(): FormArray {
    return this.form.get('serviceTimes') as FormArray;
  }

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id && id !== 'new') {
      this.isEdit = true;
      this.churchId = Number(id);
      this._loading.set(true);
      this.churchService.getById(this.churchId).subscribe({
        next: (church) => {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          this.form.patchValue(church as any);
          church.serviceTimes?.forEach((st) => this.addServiceTimeGroup(st));
          this._loading.set(false);
        },
        error: () => {
          this._loading.set(false);
        },
      });
    }
  }

  addServiceTimeGroup(st?: Partial<ServiceTime>) {
    this.serviceTimesArray.push(
      this.fb.group({
        id: [st?.id ?? null],
        dayOfWeek: [st?.dayOfWeek ?? 'Sunday'],
        time: [st?.time ?? ''],
        type: [st?.type ?? 'Sunday Service'],
        language: [st?.language ?? ''],
      })
    );
  }

  removeServiceTime(i: number) {
    this.serviceTimesArray.removeAt(i);
  }

  importFromUrl() {
    const url = this.importUrl.trim();
    if (!url) return;

    this._importing.set(true);
    this._importError.set('');
    this._importSuccess.set(false);

    this.churchService.parseUrl(url).subscribe({
      next: (data) => {
        const { serviceTimes, ...rest } = data;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        this.form.patchValue(rest as any);
        if (serviceTimes?.length) {
          while (this.serviceTimesArray.length) this.serviceTimesArray.removeAt(0);
          serviceTimes.forEach((st) => this.addServiceTimeGroup(st));
        }
        this._importing.set(false);
        this._importSuccess.set(true);
        setTimeout(() => this._importSuccess.set(false), 4000);
      },
      error: (err) => {
        this._importing.set(false);
        this._importError.set(err.error?.error ?? 'Could not fetch that URL. Try another.');
      },
    });
  }

  save() {
    if (this.form.invalid) return;
    this._saving.set(true);
    this._saveError.set('');

    const value = this.form.getRawValue() as unknown as Church;
    const obs =
      this.isEdit && this.churchId
        ? this.churchService.update(this.churchId, value)
        : this.churchService.create(value);

    obs.subscribe({
      next: (church) => this.router.navigate(['/churches', church.id]),
      error: (err) => {
        this._saving.set(false);
        // Editing an unclaimed church is Platform Admin only — a
        // 403 here is a real, expected outcome for anyone else, not just a network blip,
        // so it needs to actually say something rather than just re-enabling the button.
        this._saveError.set(
          err.status === 403
            ? "You don't have permission to save this church."
            : (err.error?.error ?? 'Could not save this church. Please try again.')
        );
      },
    });
  }
}
