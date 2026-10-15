import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { Subject, debounceTime, firstValueFrom, switchMap } from 'rxjs';
import { UserAvatarComponent } from '../../components/user-avatar.component';
import { TraditionEmblemComponent } from '../../components/tradition-emblem.component';
import { AuthService } from '../../services/auth.service';
import { UserProfileService } from '../../services/user-profile.service';
import { ChurchService } from '../../services/church.service';
import { FollowService } from '../../services/follow.service';
import { ChurchStaffService } from '../../services/church-staff.service';
import { ClaimService } from '../../services/claim.service';
import { FaithService } from '../../services/faith.service';
import { ThemePreference, ThemeService } from '../../services/theme.service';
import { Church, DENOMINATIONS } from '../../models/church.model';
import { Claim } from '../../models/claim.model';
import { MyProfile, ProfileVisibility } from '../../models/user.model';

type Tab = 'profile' | 'account' | 'preferences' | 'privacy' | 'connections';
const ROLE_LABELS: Record<string, string> = {
  MEMBER: 'Member',
  VOLUNTEER: 'Volunteer',
  CHURCH_ADMIN: 'Church Admin',
  PLATFORM_ADMIN: 'Platform Admin',
};

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [FormsModule, RouterModule, UserAvatarComponent, TraditionEmblemComponent],
  templateUrl: './settings.component.html',
})
export class SettingsComponent implements OnInit {
  protected authService = inject(AuthService);
  private userProfileService = inject(UserProfileService);
  private churchService = inject(ChurchService);
  private followService = inject(FollowService);
  private churchStaffService = inject(ChurchStaffService);
  private claimService = inject(ClaimService);
  private faithService = inject(FaithService);

  readonly DENOMINATIONS = DENOMINATIONS;
  readonly roleLabel = (role: string) => ROLE_LABELS[role] ?? role;
  readonly tabs: { id: Tab; label: string }[] = [
    { id: 'profile', label: 'Profile' },
    { id: 'account', label: 'Account' },
    { id: 'preferences', label: 'Preferences' },
    { id: 'privacy', label: 'Privacy' },
    { id: 'connections', label: 'Church connections' },
  ];

  activeTab = signal<Tab>('profile');
  loading = signal(true);
  profile = signal<MyProfile | null>(null);

  // --- Profile tab ---
  displayNameInput = '';
  usernameInput = '';
  bioInput = '';
  locationInput = '';
  traditionInput = '';
  homeChurchId?: number;
  homeChurchName = '';
  profileDirty = signal(false);
  savingProfile = signal(false);
  profileSaved = signal(false);
  profileError = signal('');

  traditionDropdownOpen = signal(false);

  usernameStatus = signal<'idle' | 'checking' | 'available' | 'taken' | 'invalid'>('idle');
  private usernameCheck = new Subject<string>();

  uploading = signal(false);
  uploadError = signal('');

  homeChurchQuery = '';
  homeChurchResults = signal<Church[]>([]);
  homeChurchDropdownOpen = signal(false);
  private homeChurchSearch = new Subject<string>();

  // --- Account tab ---
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  changingPassword = signal(false);
  passwordError = signal('');
  passwordChanged = signal(false);

  // --- Privacy tab ---
  visibilityInput: ProfileVisibility = 'PUBLIC';
  showLocationInput = false;
  showTraditionInput = true;
  showHomeChurchInput = true;
  showFollowingInput = true;
  privacyDirty = signal(false);
  savingPrivacy = signal(false);
  privacySaved = signal(false);

  // --- Church connections tab ---
  connectionsLoaded = signal(false);
  following = signal<Church[]>([]);
  managedChurches = signal<Church[]>([]);
  pendingClaims = signal<Claim[]>([]);

  // --- Preferences tab ---
  savedQuizCount = signal<number | null>(null);
  protected themeService = inject(ThemeService);
  readonly themeOptions: { id: ThemePreference; label: string }[] = [
    { id: 'system', label: 'System' },
    { id: 'light', label: 'Light' },
    { id: 'dark', label: 'Dark' },
  ];

  constructor() {
    this.usernameCheck
      .pipe(
        debounceTime(300),
        switchMap((value) => {
          if (!value) return [{ available: true }];
          if (!/^[a-z0-9_]{3,20}$/.test(value)) return [{ available: false, invalid: true } as { available: boolean; invalid?: boolean }];
          return this.userProfileService.isUsernameAvailable(value);
        })
      )
      .subscribe((res) => {
        if (!this.usernameInput) this.usernameStatus.set('idle');
        else if ('invalid' in res && res.invalid) this.usernameStatus.set('invalid');
        else this.usernameStatus.set(res.available ? 'available' : 'taken');
      });

    this.homeChurchSearch
      .pipe(
        debounceTime(300),
        switchMap((query) => (query.trim().length >= 2 ? this.churchService.search({ query: query.trim(), size: 6 }) : []))
      )
      .subscribe((result) => this.homeChurchResults.set('content' in result ? result.content : []));
  }

  ngOnInit() {
    this.userProfileService.getMyProfile().subscribe((profile) => {
      this.profile.set(profile);
      this.resetProfileForm(profile);
      this.resetPrivacyForm(profile);
      this.loading.set(false);
    });
  }

  private resetProfileForm(profile: MyProfile) {
    this.displayNameInput = profile.displayName;
    this.usernameInput = profile.username ?? '';
    this.bioInput = profile.bio ?? '';
    this.locationInput = profile.location ?? '';
    this.traditionInput = profile.tradition ?? '';
    this.homeChurchId = profile.homeChurchId;
    this.homeChurchName = profile.homeChurchName ?? '';
    this.profileDirty.set(false);
    this.usernameStatus.set('idle');
  }

  private resetPrivacyForm(profile: MyProfile) {
    this.visibilityInput = profile.profileVisibility;
    this.showLocationInput = profile.showLocation;
    this.showTraditionInput = profile.showTradition;
    this.showHomeChurchInput = profile.showHomeChurch;
    this.showFollowingInput = profile.showFollowing;
    this.privacyDirty.set(false);
  }

  selectTab(tab: Tab) {
    this.activeTab.set(tab);
    if (tab === 'connections' && !this.connectionsLoaded()) this.loadConnections();
    if (tab === 'preferences' && this.savedQuizCount() === null) {
      this.faithService.saved().subscribe({
        next: (saved) => this.savedQuizCount.set(saved.length),
        error: () => this.savedQuizCount.set(0),
      });
    }
  }

  private loadConnections() {
    this.connectionsLoaded.set(true);
    this.followService.myFollowing().subscribe({ next: (churches) => this.following.set(churches), error: () => this.following.set([]) });
    this.claimService.myClaims().subscribe({ next: (claims) => this.pendingClaims.set(claims), error: () => this.pendingClaims.set([]) });
    this.churchStaffService.myStaffChurches().subscribe({
      next: (summaries) => {
        if (summaries.length === 0) {
          this.managedChurches.set([]);
          return;
        }
        // ChurchStaffSummary only carries churchId, not the church's name/details —
        // this list is typically 0-2 churches, so a per-id fetch is simpler than a
        // new enrichment endpoint (see plan notes).
        Promise.all(summaries.map((s) => firstValueFrom(this.churchService.getById(s.churchId)))).then((churches) =>
          this.managedChurches.set(churches)
        );
      },
      error: () => this.managedChurches.set([]),
    });
  }

  // --- Profile tab actions ---

  markProfileDirty() {
    this.profileDirty.set(true);
    this.profileSaved.set(false);
  }

  onUsernameInput() {
    this.markProfileDirty();
    this.usernameStatus.set(this.usernameInput ? 'checking' : 'idle');
    this.usernameCheck.next(this.usernameInput.trim().toLowerCase());
  }

  toggleTraditionDropdown() {
    this.traditionDropdownOpen.set(!this.traditionDropdownOpen());
  }

  closeTraditionDropdown() {
    this.traditionDropdownOpen.set(false);
  }

  selectTradition(value: string) {
    this.traditionInput = value;
    this.traditionDropdownOpen.set(false);
    this.markProfileDirty();
  }

  onHomeChurchInput() {
    this.homeChurchDropdownOpen.set(true);
    this.homeChurchSearch.next(this.homeChurchQuery);
  }

  selectHomeChurch(church: Church) {
    this.homeChurchId = church.id;
    this.homeChurchName = church.name;
    this.homeChurchQuery = '';
    this.homeChurchDropdownOpen.set(false);
    this.markProfileDirty();
  }

  clearHomeChurch() {
    this.homeChurchId = undefined;
    this.homeChurchName = '';
    this.markProfileDirty();
  }

  closeHomeChurchDropdown() {
    this.homeChurchDropdownOpen.set(false);
  }

  get profileComplete(): boolean {
    return !!(this.usernameInput || this.bioInput || this.traditionInput);
  }

  saveProfile() {
    const displayName = this.displayNameInput.trim();
    if (!displayName) {
      this.profileError.set('A display name is required.');
      return;
    }
    if (this.usernameStatus() === 'taken') {
      this.profileError.set('That username is already taken.');
      return;
    }
    if (this.usernameStatus() === 'invalid') {
      this.profileError.set('Username must be 3-20 characters: lowercase letters, numbers, and underscores only.');
      return;
    }

    this.savingProfile.set(true);
    this.profileError.set('');
    this.userProfileService
      .updateProfile({
        displayName,
        username: this.usernameInput.trim() || undefined,
        bio: this.bioInput.trim() || undefined,
        location: this.locationInput.trim() || undefined,
        tradition: this.traditionInput || undefined,
        homeChurchId: this.homeChurchId ?? null,
      })
      .subscribe({
        next: (profile) => {
          this.profile.set(profile);
          this.authService.updateProfile({ displayName: profile.displayName, avatarUrl: profile.avatarUrl });
          this.resetProfileForm(profile);
          this.savingProfile.set(false);
          this.profileSaved.set(true);
        },
        error: (err) => {
          this.savingProfile.set(false);
          this.profileError.set(err.error?.error ?? 'Could not save your profile. Try again.');
        },
      });
  }

  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;

    this.uploading.set(true);
    this.uploadError.set('');

    this.userProfileService.getAvatarUploadUrl(file.type).subscribe({
      next: ({ uploadUrl, publicUrl }) => {
        this.userProfileService.uploadToS3(uploadUrl, file).subscribe({
          next: () => {
            this.userProfileService.updateAvatar(publicUrl).subscribe({
              next: (p) => {
                this.authService.updateProfile({ avatarUrl: p.avatarUrl });
                this.uploading.set(false);
              },
              error: () => this.failUpload('Upload succeeded, but saving your avatar failed. Try again.'),
            });
          },
          error: () => this.failUpload('Could not upload that file to storage. Try again.'),
        });
      },
      error: (err) => this.failUpload(err.error?.error ?? 'Could not start the upload. Avatar uploads may not be configured yet.'),
    });
  }

  removePhoto() {
    this.uploading.set(true);
    this.userProfileService.updateAvatar('').subscribe({
      next: (p) => {
        this.authService.updateProfile({ avatarUrl: p.avatarUrl });
        this.uploading.set(false);
      },
      error: () => this.failUpload('Could not remove your photo. Try again.'),
    });
  }

  private failUpload(message: string) {
    this.uploading.set(false);
    this.uploadError.set(message);
  }

  // --- Account tab actions ---

  changePassword() {
    this.passwordChanged.set(false);
    if (this.newPassword.length < 8) {
      this.passwordError.set('New password must be at least 8 characters.');
      return;
    }
    if (this.newPassword !== this.confirmPassword) {
      this.passwordError.set("New password and confirmation don't match.");
      return;
    }

    this.changingPassword.set(true);
    this.passwordError.set('');
    this.userProfileService.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.changingPassword.set(false);
        this.passwordChanged.set(true);
        this.currentPassword = '';
        this.newPassword = '';
        this.confirmPassword = '';
      },
      error: (err) => {
        this.changingPassword.set(false);
        this.passwordError.set(err.error?.error ?? 'Could not change your password. Try again.');
      },
    });
  }

  // --- Privacy tab actions ---

  markPrivacyDirty() {
    this.privacyDirty.set(true);
    this.privacySaved.set(false);
  }

  savePrivacy() {
    this.savingPrivacy.set(true);
    this.userProfileService
      .updatePrivacy({
        profileVisibility: this.visibilityInput,
        showLocation: this.showLocationInput,
        showTradition: this.showTraditionInput,
        showHomeChurch: this.showHomeChurchInput,
        showFollowing: this.showFollowingInput,
      })
      .subscribe({
        next: (profile) => {
          this.profile.set(profile);
          this.resetPrivacyForm(profile);
          this.savingPrivacy.set(false);
          this.privacySaved.set(true);
        },
        error: () => this.savingPrivacy.set(false),
      });
  }
}
