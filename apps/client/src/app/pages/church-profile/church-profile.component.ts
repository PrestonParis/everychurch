import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { Church, ServiceTime } from '../../models/church.model';
import { ChurchService } from '../../services/church.service';

@Component({
  selector: 'app-church-profile',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './church-profile.component.html',
})
export class ChurchProfileComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private churchService = inject(ChurchService);

  church: Church | null = null;
  loading = true;
  error = false;

  private readonly DAY_ORDER = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.churchService.getById(id).subscribe({
      next: (data) => {
        this.church = data;
        this.loading = false;
      },
      error: () => {
        this.error = true;
        this.loading = false;
      },
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
