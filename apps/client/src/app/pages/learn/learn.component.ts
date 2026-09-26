import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FaithCatalog, FaithTradition } from '../../models/faith.model';
import { FaithService, faithError } from '../../services/faith.service';

@Component({
  selector: 'app-learn',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './learn.component.html',
  styleUrl: './faith.scss',
})
export class LearnComponent implements OnInit {
  private service = inject(FaithService);
  private route = inject(ActivatedRoute);
  catalog = signal<FaithCatalog | null>(null);
  tradition = signal<FaithTradition | null>(null);
  error = signal('');
  slug = this.route.snapshot.paramMap.get('slug');
  ngOnInit() {
    this.service.catalog().subscribe({
      next: (c) => {
        this.catalog.set(c);
        if (this.slug) {
          this.tradition.set(
            c.traditions.find((t) => t.id === this.slug) ?? null,
          );
          if (!this.tradition())
            this.error.set(
              'This tradition is not yet in the reference library.',
            );
        }
      },
      error: (e) => this.error.set(faithError(e)),
    });
  }
  position(id: string, option: string) {
    const d = this.catalog()?.dimensions.find((d) => d.id === id);
    return {
      title: d?.title ?? id,
      label: d?.options.find((o) => o.id === option)?.label ?? option,
    };
  }
  entries(t: FaithTradition) {
    return Object.entries(t.answers).map(([id, value]) =>
      this.position(id, value),
    );
  }
}
