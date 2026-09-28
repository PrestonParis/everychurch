import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { UserAvatarComponent } from '../../components/user-avatar.component';
import { TraditionEmblemComponent } from '../../components/tradition-emblem.component';
import { PublicProfileService } from '../../services/public-profile.service';
import { PublicUserProfile } from '../../models/user.model';

@Component({
  selector: 'app-public-profile',
  standalone: true,
  imports: [RouterModule, UserAvatarComponent, TraditionEmblemComponent],
  templateUrl: './public-profile.component.html',
})
export class PublicProfileComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private publicProfileService = inject(PublicProfileService);

  loading = signal(true);
  notFound = signal(false);
  profile = signal<PublicUserProfile | null>(null);

  ngOnInit() {
    const username = this.route.snapshot.paramMap.get('username') ?? '';
    this.publicProfileService.get(username).subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.loading.set(false);
      },
      error: () => {
        this.notFound.set(true);
        this.loading.set(false);
      },
    });
  }
}
