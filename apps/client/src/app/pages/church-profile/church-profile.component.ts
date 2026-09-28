import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { Church, ServiceTime } from '../../models/church.model';
import { ChurchImage } from '../../models/church-image.model';
import { Sermon } from '../../models/sermon.model';
import { ChurchEvent } from '../../models/event.model';
import { ChurchService } from '../../services/church.service';
import { ChurchImageService } from '../../services/church-image.service';
import { SermonService } from '../../services/sermon.service';
import { EventService } from '../../services/event.service';
import { AuthService } from '../../services/auth.service';
import { ChurchCommunityComponent } from './church-community.component';
import { ChurchClaimComponent } from './church-claim.component';
import { MemberService } from '../../services/member.service';
import { MembershipStatus } from '../../models/member.model';
import { FavoriteButtonComponent } from '../../components/favorite-button.component';
import { FollowButtonComponent } from '../../components/follow-button.component';
import { ChurchPostsComponent } from './church-posts.component';
import { ChurchStaffService } from '../../services/church-staff.service';
import { ChurchPermission } from '../../models/church-staff.model';
import { ChurchMapComponent } from '../../components/church-map.component';
import { TraditionEmblemComponent } from '../../components/tradition-emblem.component';
import { denomStyle, tintColor, titleCase } from '../../utils/church-display.util';

@Component({
  selector: 'app-church-profile',
  standalone: true,
  imports: [
    RouterModule, ChurchCommunityComponent, ChurchClaimComponent,
    FavoriteButtonComponent, FollowButtonComponent, ChurchPostsComponent,
    ChurchMapComponent, TraditionEmblemComponent,
  ],
  templateUrl: './church-profile.component.html',
})
export class ChurchProfileComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private churchService = inject(ChurchService);
  private churchImageService = inject(ChurchImageService);
  private sermonService = inject(SermonService);
  private eventService = inject(EventService);
  private memberService = inject(MemberService);
  private churchStaffService = inject(ChurchStaffService);
  authService = inject(AuthService);

  // Backed by signals, exposed as plain-looking properties so the template reads them
  // unchanged. The app is zoneless (no zone.js), so a plain field assigned inside an HTTP
  // callback never triggers a re-render — the spinner would spin forever. A signal write
  // is what schedules the render.
  private readonly _church = signal<Church | null>(null);
  private readonly _loading = signal(true);
  private readonly _error = signal(false);
  private readonly _status = signal<MembershipStatus | null>(null);
  // This user's permission set specifically for THIS church (empty until it's
  // fetched, or for an unclaimed church where there's no ChurchStaff row to have
  // one — canManage's unclaimed-church branch below covers that case instead).
  private readonly _myPermissions = signal<Set<ChurchPermission>>(new Set());
  get church(): Church | null { return this._church(); }
  get loading(): boolean { return this._loading(); }
  get error(): boolean { return this._error(); }

  // Gallery, sermons, and events are independent of the core profile load — a
  // failure loading any of them shouldn't block the rest of the page.
  images = signal<ChurchImage[]>([]);
  sermons = signal<Sermon[]>([]);
  upcomingEvents = signal<ChurchEvent[]>([]);

  private readonly DAY_ORDER = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.churchService.getById(id).subscribe({
      next: (data) => {
        this._church.set(data);
        this._loading.set(false);
      },
      error: () => {
        this._error.set(true);
        this._loading.set(false);
      },
    });

    // Fire-and-forget — feeds "recently viewed" on the Favorites page. Only logged-in
    // viewers have a Favorites page to see it on, so there's nothing to record otherwise.
    if (this.authService.isAuthenticated()) {
      this.churchService.recordView(id).subscribe({ error: () => {} });
    }

    if (this.authService.isAuthenticated()) {
      this.memberService.status(id).subscribe({
        next: (status) => this._status.set(status),
        error: () => this._status.set(null),
      });

      // Powers the per-control checks below (canEditProfile/canManageSermons/etc) —
      // a plain "is staff" boolean used to gate Edit/Photos/Sermons/+Event as one
      // group, so someone appointed for events-only was still offered Edit/Photos/
      // Sermons buttons the server would reject.
      this.churchStaffService.myStaffChurches().subscribe({
        next: (summaries) => {
          const mine = summaries.find((s) => s.churchId === id);
          this._myPermissions.set(new Set(mine?.permissions ?? []));
        },
        error: () => this._myPermissions.set(new Set()),
      });
    }

    this.churchImageService.list(id).subscribe({
      next: (images) => this.images.set([...images].sort((a, b) => a.sortOrder - b.sortOrder)),
      error: () => this.images.set([]),
    });

    this.sermonService.list(id).subscribe({
      next: (sermons) => this.sermons.set(sermons),
      error: () => this.sermons.set([]),
    });

    this.eventService.list(id).subscribe({
      next: (events) => {
        const now = Date.now();
        this.upcomingEvents.set(
          events
            .filter((e) => new Date(e.startsAt).getTime() >= now)
            .sort((a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime())
        );
      },
      error: () => this.upcomingEvents.set([]),
    });
  }

  /**
   * Whether this person can manage ANYTHING about this church — used to decide
   * whether the management area (the Edit/Photos/Sermons/+Event row, the post
   * composer) is worth showing at all. Individual controls within it are gated more
   * specifically below (canEditProfile/canManageSermons/canManageEvents/
   * canManagePosts) — found in review that a staff member appointed for one thing
   * (say, events) was shown every control regardless, because this alone used to
   * gate all of them as one group; the server always enforced the real permission
   * correctly, this was purely an over-offered client-side button.
   */
  get canManage(): boolean {
    return this.canEditProfile || this.canManageSermons || this.canManageEvents
      || this.canManagePosts || this.canManageStaff;
  }

  get canEditProfile(): boolean { return this.hasPermission('EDIT_PROFILE'); }
  get canManageSermons(): boolean { return this.hasPermission('MANAGE_SERMONS'); }
  get canManageEvents(): boolean { return this.hasPermission('MANAGE_EVENTS'); }
  get canManagePosts(): boolean { return this.hasPermission('MANAGE_POSTS'); }
  get canManageStaff(): boolean { return this.hasPermission('MANAGE_STAFF'); }

  /**
   * Mirrors the server's rule (ChurchService/EventService/PostService's
   * requireCanManage-style checks) exactly: once claimed, needs this specific
   * permission (or Platform Admin); an unclaimed listing has no owner yet, so — per
   * project-owner feedback (docs/PROMPTS.md) — only a Platform Admin may touch it at
   * all, regardless of which permission is being asked about (there's no ChurchStaff
   * row to have one). Everyone else still has a path in: the ChurchSubmission review
   * queue for volunteers.
   */
  private hasPermission(permission: ChurchPermission): boolean {
    const church = this.church;
    if (!church || !this.authService.isAuthenticated()) return false;
    if (this.authService.currentUser()?.role === 'PLATFORM_ADMIN') return true;
    if (!church.claimed) return false;
    return this._myPermissions().has(permission);
  }

  isVideo(url: string): boolean {
    return /\.(mp4|webm|mov|m4v)$/i.test(url);
  }

  formatEventDate(iso: string): string {
    return new Date(iso).toLocaleString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }

  get sortedServiceTimes(): ServiceTime[] {
    if (!this.church?.serviceTimes) return [];
    return [...this.church.serviceTimes].sort(
      (a, b) => this.DAY_ORDER.indexOf(a.dayOfWeek) - this.DAY_ORDER.indexOf(b.dayOfWeek)
    );
  }

  get initials(): string {
    return (
      this.church?.name
        .split(' ')
        .filter((w) => w.length > 2)
        .slice(0, 2)
        .map((w) => w[0])
        .join('') ?? ''
    );
  }

  get fullAddress(): string {
    const parts = [this.church?.address, this.church?.city, this.church?.state, this.church?.zipCode];
    return parts.filter(Boolean).join(', ');
  }

  mapsUrl(address: string): string {
    return `https://maps.google.com/?q=${encodeURIComponent(address)}`;
  }

  truncate(url: string, len = 32): string {
    return url.length > len ? url.substring(0, len) + '…' : url;
  }

  /** Same curated per-tradition color as the directory cards (see utils/church-display.util) — a
   *  tradition reads as the same color everywhere, instead of this page inventing its own. */
  denomStyle = denomStyle;
  tint = tintColor;
  titleCase = titleCase;

  serviceTypeBadge(type: string): string {
    const map: Record<string, string> = {
      'Mass': 'bg-purple-50 text-purple-700',
      'Sunday Service': 'bg-indigo-50 text-indigo-700',
      'Bible Study': 'bg-amber-50 text-amber-700',
      'Youth Group': 'bg-green-50 text-green-700',
      'Prayer Meeting': 'bg-blue-50 text-blue-700',
      'Spanish Service': 'bg-orange-50 text-orange-700',
      'Choir / Worship Practice': 'bg-rose-50 text-rose-700',
      'Evening Service': 'bg-violet-50 text-violet-700',
    };
    return map[type] ?? 'bg-slate-50 text-slate-600';
  }
}
