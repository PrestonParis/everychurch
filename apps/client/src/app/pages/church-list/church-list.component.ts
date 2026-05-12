import { Component, OnInit, inject } from '@angular/core';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Church } from '../../models/church.model';
import { ChurchService } from '../../services/church.service';

@Component({
  selector: 'app-church-list',
  standalone: true,
  imports: [RouterModule, FormsModule],
  templateUrl: './church-list.component.html',
})
export class ChurchListComponent implements OnInit {
  private churchService = inject(ChurchService);

  churches: Church[] = [];
  filtered: Church[] = [];
  query = '';
  loading = true;
  error = false;

  ngOnInit() {
    this.churchService.getAll().subscribe({
      next: (data) => {
        this.churches = data;
        this.filtered = data;
        this.loading = false;
      },
      error: () => {
        this.error = true;
        this.loading = false;
      },
    });
  }

  onSearch() {
    const q = this.query.toLowerCase();
    this.filtered = q
      ? this.churches.filter(
          (c) =>
            c.name.toLowerCase().includes(q) ||
            c.denomination?.toLowerCase().includes(q) ||
            c.city?.toLowerCase().includes(q) ||
            c.state?.toLowerCase().includes(q)
        )
      : this.churches;
  }

  initials(name: string): string {
    return name
      .split(' ')
      .filter((w) => w.length > 2)
      .slice(0, 2)
      .map((w) => w[0])
      .join('');
  }

  denominationBadge(denomination?: string): string {
    const map: Record<string, string> = {
      'Catholic': 'bg-purple-100 text-purple-700',
      'Baptist': 'bg-blue-100 text-blue-700',
      'Methodist': 'bg-teal-100 text-teal-700',
      'Lutheran': 'bg-orange-100 text-orange-700',
      'Presbyterian': 'bg-green-100 text-green-700',
      'Episcopal/Anglican': 'bg-red-100 text-red-700',
      'Pentecostal / Assembly of God': 'bg-amber-100 text-amber-700',
      'Non-denominational': 'bg-slate-100 text-slate-700',
      'Evangelical': 'bg-sky-100 text-sky-700',
    };
    return denomination ? (map[denomination] ?? 'bg-indigo-100 text-indigo-700') : 'bg-gray-100 text-gray-600';
  }

  avatarBg(denomination?: string): string {
    const map: Record<string, string> = {
      'Catholic': 'bg-purple-600',
      'Baptist': 'bg-blue-600',
      'Methodist': 'bg-teal-600',
      'Lutheran': 'bg-orange-600',
      'Presbyterian': 'bg-green-600',
      'Episcopal/Anglican': 'bg-red-600',
      'Pentecostal / Assembly of God': 'bg-amber-600',
      'Non-denominational': 'bg-slate-600',
      'Evangelical': 'bg-sky-600',
    };
    return denomination ? (map[denomination] ?? 'bg-indigo-600') : 'bg-slate-500';
  }
}
