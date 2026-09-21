import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ChurchImage } from '../../models/church-image.model';
import { ChurchImageService } from '../../services/church-image.service';

@Component({
  selector: 'app-church-images',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './church-images.component.html',
})
export class ChurchImagesComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private churchImageService = inject(ChurchImageService);

  churchId = 0;

  images = signal<ChurchImage[]>([]);
  loading = signal(true);
  loadError = signal(false);

  uploading = signal(false);
  uploadError = signal('');

  reordering = signal<number | null>(null); // image id currently being moved, disables its buttons

  ngOnInit() {
    this.churchId = Number(this.route.snapshot.paramMap.get('id'));
    this.reload();
  }

  private reload() {
    this.loading.set(true);
    this.loadError.set(false);
    this.churchImageService.list(this.churchId).subscribe({
      next: (images) => {
        this.images.set([...images].sort((a, b) => a.sortOrder - b.sortOrder));
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
    const file = input.files?.[0];
    input.value = ''; // allow re-selecting the same file later
    if (!file) return;

    this.uploading.set(true);
    this.uploadError.set('');

    const nextSortOrder = this.images().length
      ? Math.max(...this.images().map((i) => i.sortOrder)) + 1
      : 0;

    this.churchImageService.getUploadUrl(this.churchId, file.type).subscribe({
      next: ({ uploadUrl, publicUrl }) => {
        this.churchImageService.uploadToS3(uploadUrl, file).subscribe({
          next: () => {
            this.churchImageService.create(this.churchId, publicUrl, nextSortOrder).subscribe({
              next: () => {
                this.uploading.set(false);
                this.reload();
              },
              error: () => this.failUpload('Upload succeeded, but saving the image failed. Try again.'),
            });
          },
          error: () => this.failUpload('Could not upload that file to storage. Try again.'),
        });
      },
      error: (err) =>
        this.failUpload(err.error?.error ?? 'Could not start the upload. Image uploads may not be configured yet.'),
    });
  }

  private failUpload(message: string) {
    this.uploading.set(false);
    this.uploadError.set(message);
  }

  moveUp(index: number) {
    if (index === 0) return;
    this.swap(index, index - 1);
  }

  moveDown(index: number) {
    if (index === this.images().length - 1) return;
    this.swap(index, index + 1);
  }

  private swap(i: number, j: number) {
    const images = this.images();
    const a = images[i];
    const b = images[j];
    this.reordering.set(a.id);

    this.churchImageService.updatePosition(this.churchId, a.id, a.url, b.sortOrder).subscribe({
      next: () => {
        this.churchImageService.updatePosition(this.churchId, b.id, b.url, a.sortOrder).subscribe({
          next: () => {
            this.reordering.set(null);
            this.reload();
          },
          error: () => this.reordering.set(null),
        });
      },
      error: () => this.reordering.set(null),
    });
  }

  remove(image: ChurchImage) {
    this.reordering.set(image.id);
    this.churchImageService.delete(this.churchId, image.id).subscribe({
      next: () => {
        this.reordering.set(null);
        this.reload();
      },
      error: () => this.reordering.set(null),
    });
  }
}
