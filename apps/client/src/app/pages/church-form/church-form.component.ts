import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormArray, FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { Church, ServiceTime } from '../../models/church.model';
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
  importing = false;
  importError = '';
  importSuccess = false;

  isEdit = false;
  churchId: number | null = null;
  loading = false;
  saving = false;

  readonly DENOMINATIONS = [
    'Baptist',
    'Catholic',
    'Church of Christ',
    'Eastern Orthodox',
    'Episcopal/Anglican',
    'Evangelical',
    'Lutheran',
    'Methodist',
    'Non-denominational',
    'Pentecostal / Assembly of God',
    'Presbyterian',
    'Seventh-day Adventist',
    'Other',
  ];

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
      this.loading = true;
      this.churchService.getById(this.churchId).subscribe({
        next: (church) => {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          this.form.patchValue(church as any);
          church.serviceTimes?.forEach((st) => this.addServiceTimeGroup(st));
          this.loading = false;
        },
        error: () => {
          this.loading = false;
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

    this.importing = true;
    this.importError = '';
    this.importSuccess = false;

    this.churchService.parseUrl(url).subscribe({
      next: (data) => {
        const { serviceTimes, ...rest } = data;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        this.form.patchValue(rest as any);
        if (serviceTimes?.length) {
          while (this.serviceTimesArray.length) this.serviceTimesArray.removeAt(0);
          serviceTimes.forEach((st) => this.addServiceTimeGroup(st));
        }
        this.importing = false;
        this.importSuccess = true;
        setTimeout(() => (this.importSuccess = false), 4000);
      },
      error: (err) => {
        this.importing = false;
        this.importError = err.error?.error ?? 'Could not fetch that URL. Try another.';
      },
    });
  }

  save() {
    if (this.form.invalid) return;
    this.saving = true;

    const value = this.form.getRawValue() as unknown as Church;
    const obs =
      this.isEdit && this.churchId
        ? this.churchService.update(this.churchId, value)
        : this.churchService.create(value);

    obs.subscribe({
      next: (church) => this.router.navigate(['/churches', church.id]),
      error: () => (this.saving = false),
    });
  }
}
