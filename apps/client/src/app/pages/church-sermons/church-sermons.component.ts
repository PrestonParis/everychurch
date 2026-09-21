import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Sermon } from '../../models/sermon.model';
import { SermonService } from '../../services/sermon.service';

@Component({
  selector: 'app-church-sermons',
  standalone: true,
  imports: [RouterModule, FormsModule],
  templateUrl: './church-sermons.component.html',
})
export class ChurchSermonsComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private sermonService = inject(SermonService);

  churchId = 0;

  sermons = signal<Sermon[]>([]);
  loading = signal(true);
  loadError = signal(false);

  title = '';
  date = '';
  selectedFile: File | null = null;

  saving = signal(false);
  saveError = signal('');

  removingId = signal<number | null>(null);

  ngOnInit() {
    this.churchId = Number(this.route.snapshot.paramMap.get('id'));
    this.reload();
  }

  private reload() {
    this.loading.set(true);
    this.loadError.set(false);
    this.sermonService.list(this.churchId).subscribe({
      next: (sermons) => {
        this.sermons.set(sermons);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files?.[0] ?? null;
  }

  get canSubmit(): boolean {
    return !!this.title.trim() && !!this.date && !this.saving();
  }

  addSermon() {
    if (!this.canSubmit) return;

    this.saving.set(true);
    this.saveError.set('');

    if (!this.selectedFile) {
      this.createSermon(undefined);
      return;
    }

    const file = this.selectedFile;
    this.sermonService.getUploadUrl(this.churchId, file.type).subscribe({
      next: ({ uploadUrl, publicUrl }) => {
        this.sermonService.uploadToS3(uploadUrl, file).subscribe({
          next: () => this.createSermon(publicUrl),
          error: () => this.failSave('Could not upload that file to storage. Try again.'),
        });
      },
      error: (err) =>
        this.failSave(err.error?.error ?? 'Could not start the upload. Sermon uploads may not be configured yet.'),
    });
  }

  private createSermon(mediaUrl: string | undefined) {
    this.sermonService.create(this.churchId, { title: this.title.trim(), date: this.date, mediaUrl }).subscribe({
      next: () => {
        this.title = '';
        this.date = '';
        this.selectedFile = null;
        this.saving.set(false);
        this.reload();
      },
      error: () => this.failSave('Could not save the sermon. Try again.'),
    });
  }

  private failSave(message: string) {
    this.saving.set(false);
    this.saveError.set(message);
  }

  remove(sermon: Sermon) {
    if (!sermon.id) return;
    this.removingId.set(sermon.id);
    this.sermonService.delete(this.churchId, sermon.id).subscribe({
      next: () => {
        this.removingId.set(null);
        this.reload();
      },
      error: () => this.removingId.set(null),
    });
  }
}
