import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { UserAvatarComponent } from '../../components/user-avatar.component';
import { AuthService } from '../../services/auth.service';
import { UserProfileService } from '../../services/user-profile.service';

@Component({
  selector: 'app-account',
  standalone: true,
  imports: [FormsModule, UserAvatarComponent],
  templateUrl: './account.component.html',
})
export class AccountComponent {
  protected authService = inject(AuthService);
  private userProfileService = inject(UserProfileService);

  displayNameInput = this.authService.currentUser()?.displayName ?? '';

  savingName = signal(false);
  nameError = signal('');
  nameSaved = signal(false);

  uploading = signal(false);
  uploadError = signal('');

  saveDisplayName() {
    const trimmed = this.displayNameInput.trim();
    if (!trimmed) {
      this.nameError.set('A display name is required.');
      return;
    }

    this.savingName.set(true);
    this.nameError.set('');
    this.nameSaved.set(false);

    this.userProfileService.updateDisplayName(trimmed).subscribe({
      next: (profile) => {
        this.authService.updateProfile({ displayName: profile.displayName });
        this.savingName.set(false);
        this.nameSaved.set(true);
      },
      error: (err) => {
        this.savingName.set(false);
        this.nameError.set(err.error?.error ?? 'Could not save that name. Try again.');
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

    this.userProfileService.getAvatarUploadUrl(file.type).subscribe({
      next: ({ uploadUrl, publicUrl }) => {
        this.userProfileService.uploadToS3(uploadUrl, file).subscribe({
          next: () => {
            this.userProfileService.updateAvatar(publicUrl).subscribe({
              next: (profile) => {
                this.authService.updateProfile({ avatarUrl: profile.avatarUrl });
                this.uploading.set(false);
              },
              error: () => this.failUpload('Upload succeeded, but saving your avatar failed. Try again.'),
            });
          },
          error: () => this.failUpload('Could not upload that file to storage. Try again.'),
        });
      },
      error: (err) =>
        this.failUpload(err.error?.error ?? 'Could not start the upload. Avatar uploads may not be configured yet.'),
    });
  }

  private failUpload(message: string) {
    this.uploading.set(false);
    this.uploadError.set(message);
  }
}
