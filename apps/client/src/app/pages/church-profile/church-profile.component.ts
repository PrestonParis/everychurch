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

@Component({
  selector: 'app-church-profile',
  standalone: true,
  imports: [RouterModule, ChurchCommunityComponent, ChurchClaimComponent],
  templateUrl: './church-profile.component.html',
})
export class ChurchProfileComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private churchService = inject(ChurchService);
  private churchImageService = inject(ChurchImageService);
  private sermonService = inject(SermonService);
  private eventService = inject(EventService);
  private memberService = inject(MemberService);
  authService = inject(AuthService);

  // Backed by signals, exposed as plain-looking properties so the template reads them
  // unchanged. The app is zoneless (no zone.js), so a plain field assigned inside an HTTP
  // callback never triggers a re-render — the spinner would spin forever. A signal write
  // is what schedules the render.
  private readonly _church = signal<Church | null>(null);
  private readonly _loading = signal(true);
  private readonly _error = signal(false);
  private readonly _status = signal<MembershipStatus | null>(null);
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

    if (this.authService.isAuthenticated()) {
      this.memberService.status(id).subscribe({
        next: (status) => this._status.set(status),
        error: () => this._status.set(null),
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
   * Whether to show the Edit/Photos/Sermons/Event controls. Mirrors the server's rule so
   * nobody is offered a button that ends in a refusal: an unclaimed listing has no owner yet
   * so any logged-in user may edit it; once claimed, only its staff (or a Platform Admin).
   * For a claimed church we hide the controls until we know the answer rather than flash them.
   */
  get canManage(): boolean {
    const church = this.church;
    if (!church || !this.authService.isAuthenticated()) return false;
    if (!church.claimed) return true;
    return this.authService.currentUser()?.role === 'PLATFORM_ADMIN' || this._status()?.staff === true;
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

  denominationBadge(denomination?: string): string {
    const map: Record<string, string> = {
      'Catholic': 'bg-purple-500/20 border-purple-400/30 text-purple-200',
      'Baptist': 'bg-blue-500/20 border-blue-400/30 text-blue-200',
      'Methodist': 'bg-teal-500/20 border-teal-400/30 text-teal-200',
      'Lutheran': 'bg-orange-500/20 border-orange-400/30 text-orange-200',
      'Presbyterian': 'bg-green-500/20 border-green-400/30 text-green-200',
      'Episcopal/Anglican': 'bg-red-500/20 border-red-400/30 text-red-200',
      'Pentecostal / Assembly of God': 'bg-amber-500/20 border-amber-400/30 text-amber-200',
      'Non-denominational': 'bg-slate-500/20 border-slate-400/30 text-slate-200',
      'Evangelical': 'bg-sky-500/20 border-sky-400/30 text-sky-200',
    };
    return denomination
      ? (map[denomination] ?? 'bg-indigo-500/20 border-indigo-400/30 text-indigo-200')
      : 'bg-white/10 border-white/20 text-white/70';
  }

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
