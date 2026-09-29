import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FaithCatalog, FaithDimension, FaithTradition } from '../../models/faith.model';
import { FaithService, faithError } from '../../services/faith.service';

/**
 * Which broader family a reference tradition belongs to, and — per the project
 * owner's product-review feedback — its specific-church-body vs. tradition level.
 * Client-side/presentational only: the catalog data itself isn't changing this
 * round, so this is a lookup table, not a new schema field. Order here also
 * controls display order in the traditions grid (roughly historical: Catholic,
 * Orthodox, then Protestant traditions grouped by lineage, then Restorationist).
 */
export const TRADITION_FAMILIES: Record<string, { family: string; bodyOf?: string; denomination: string }> = {
  'catholic': { family: 'Catholic', denomination: 'Catholic' },
  'eastern-orthodox': { family: 'Eastern Orthodox', denomination: 'Eastern Orthodox' },
  'lcms': { family: 'Protestant · Lutheran', bodyOf: 'Lutheran', denomination: 'Lutheran' },
  'episcopal': { family: 'Protestant · Anglican', bodyOf: 'Anglican', denomination: 'Episcopal/Anglican' },
  'pcusa': { family: 'Protestant · Reformed/Presbyterian', bodyOf: 'Presbyterian', denomination: 'Presbyterian' },
  'southern-baptist': { family: 'Protestant · Baptist', bodyOf: 'Baptist', denomination: 'Baptist' },
  'united-methodist': { family: 'Protestant · Methodist/Wesleyan', bodyOf: 'Methodist', denomination: 'Methodist' },
  'assemblies-of-god': { family: 'Protestant · Pentecostal', bodyOf: 'Pentecostal', denomination: 'Pentecostal / Assembly of God' },
  'seventh-day-adventist': { family: 'Protestant · Adventist', denomination: 'Seventh-day Adventist' },
  // Deliberately not filed under "Protestant" — its own claimed relationship to
  // Nicene Christianity is disputed, so this names it as its own family rather
  // than taking a side (see the project owner's note on precise, non-polemical
  // wording for this specific tradition).
  'latter-day-saint': { family: 'Restorationist · Latter-day Saint movement', denomination: 'Latter-day Saint (LDS)' },
};

/** Groups the catalog's flat dimension list into named sections for the tradition
 *  profile page — replaces one undifferentiated grid of equal-sized cards with a
 *  page that reads like chapters. Every dimension id must appear in exactly one
 *  section; dimensions not yet in this map still need a home (falls through to
 *  "More" via sectionsFor below) rather than silently vanishing. */
const SECTION_DEFINITIONS: { id: string; title: string; dimensionIds: string[] }[] = [
  { id: 'authority', title: 'Authority & Scripture', dimensionIds: ['godhead', 'scripture', 'authority'] },
  { id: 'salvation', title: 'Salvation', dimensionIds: ['salvation'] },
  { id: 'baptism', title: 'Baptism', dimensionIds: ['baptism-recipients', 'baptism-meaning', 'baptism-mode'] },
  { id: 'communion', title: 'Communion', dimensionIds: ['communion', 'communion-frequency'] },
  { id: 'gifts', title: 'Spiritual gifts', dimensionIds: ['gifts', 'tongues'] },
  { id: 'ministry', title: 'Church & ministry', dimensionIds: ['governance'] },
  { id: 'worship', title: 'Worship', dimensionIds: ['worship', 'sabbath'] },
  {
    id: 'devotion',
    title: 'Prayer & devotional life',
    dimensionIds: ['saints', 'confession', 'rosary', 'adoration', 'icons', 'daily-prayer', 'bible-study', 'fasting', 'prayer-groups', 'service'],
  },
];

/** The hub's "what you'll find inside a profile" preview — an honest table of
 *  contents rather than a nav-shaped dead end: no per-topic pages exist yet, so
 *  this doesn't pretend to link anywhere, it just sets accurate expectations for
 *  what's actually behind "Explore Christian traditions" below. */
export const HUB_TOPIC_GROUPS: { title: string; topics: string[] }[] = [
  { title: 'Beliefs & doctrine', topics: ['God & the Trinity', 'Scripture', 'Teaching authority', 'Salvation', 'Baptism', 'Communion', 'Spiritual gifts', 'Church leadership'] },
  { title: 'Worship & practice', topics: ['Pattern of worship', 'Communion frequency', 'Sabbath observance'] },
  { title: 'Prayer & devotional life', topics: ['Saints', 'Confession', 'Rosary', 'Icons', 'Daily prayer', 'Bible study', 'Fasting', 'Works of mercy'] },
];

export interface TraditionSection {
  id: string;
  title: string;
  entries: { title: string; question: string; label: string; explanation: string }[];
}

export interface QuickFact {
  label: string;
  value: string;
}

/** The handful of dimensions surfaced as a compact "quick facts" strip at the top
 *  of a tradition profile, before the full sectioned read-through. */
const QUICK_FACT_DIMENSION_IDS = ['governance', 'baptism-recipients', 'communion', 'worship'];

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
  readonly hubTopicGroups = HUB_TOPIC_GROUPS;

  ngOnInit() {
    this.service.catalog().subscribe({
      next: (c) => {
        this.catalog.set(c);
        if (this.slug) {
          this.tradition.set(c.traditions.find((t) => t.id === this.slug) ?? null);
          if (!this.tradition()) this.error.set('This tradition is not yet in the reference library.');
        }
      },
      error: (e) => this.error.set(faithError(e, 'quiz')),
    });
  }

  /** e.g. "Protestant · Baptist" — falls back to "Christian tradition" for
   *  anything not yet in TRADITION_FAMILIES rather than showing nothing. */
  family(traditionId: string): string {
    return TRADITION_FAMILIES[traditionId]?.family ?? 'Christian tradition';
  }

  /** Just the part before "·", for grouping the traditions grid by broad family
   *  (Catholic / Eastern Orthodox / Protestant / Restorationist) rather than one
   *  flat list mixing tradition-level and specific-church-body entries. */
  topLevelFamily(traditionId: string): string {
    return this.family(traditionId).split('·')[0].trim();
  }

  groupedTraditions(c: FaithCatalog): { family: string; traditions: FaithTradition[] }[] {
    const order: string[] = [];
    const byFamily = new Map<string, FaithTradition[]>();
    for (const t of c.traditions) {
      const top = this.topLevelFamily(t.id);
      if (!byFamily.has(top)) {
        byFamily.set(top, []);
        order.push(top);
      }
      byFamily.get(top)!.push(t);
    }
    return order.map((family) => ({ family, traditions: byFamily.get(family)! }));
  }

  /** The app's curated DENOMINATIONS label this tradition corresponds to (see
   *  church.model.ts) — what ChurchListComponent's denomination filter actually
   *  matches against server-side. Deliberately NOT the tradition's own display
   *  name (e.g. "Lutheran — Missouri Synod"), which the search endpoint's
   *  denomination matcher wouldn't recognize at all. */
  denominationFilter(traditionId: string): string | null {
    return TRADITION_FAMILIES[traditionId]?.denomination ?? null;
  }

  private dimension(id: string): FaithDimension | undefined {
    return this.catalog()?.dimensions.find((d) => d.id === id);
  }

  private optionLabel(d: FaithDimension, optionId: string): string {
    return d.options.find((o) => o.id === optionId)?.label ?? optionId;
  }

  /** Every section that has at least one answered dimension for this tradition,
   *  in SECTION_DEFINITIONS order, plus a trailing "More" section for anything
   *  left over — so a future dimension added to the catalog without an entry
   *  here still shows up somewhere instead of silently disappearing. */
  sectionsFor(t: FaithTradition): TraditionSection[] {
    const seen = new Set<string>();
    const sections: TraditionSection[] = [];

    for (const def of SECTION_DEFINITIONS) {
      const entries = def.dimensionIds
        .filter((id) => t.answers[id] !== undefined)
        .map((id) => {
          seen.add(id);
          const d = this.dimension(id)!;
          return { title: d.title, question: d.question, label: this.optionLabel(d, t.answers[id]), explanation: d.explanation };
        });
      if (entries.length > 0) sections.push({ id: def.id, title: def.title, entries });
    }

    const leftover = Object.keys(t.answers).filter((id) => !seen.has(id) && this.dimension(id));
    if (leftover.length > 0) {
      sections.push({
        id: 'more',
        title: 'More',
        entries: leftover.map((id) => {
          const d = this.dimension(id)!;
          return { title: d.title, question: d.question, label: this.optionLabel(d, t.answers[id]), explanation: d.explanation };
        }),
      });
    }
    return sections;
  }

  quickFacts(t: FaithTradition): QuickFact[] {
    return QUICK_FACT_DIMENSION_IDS.filter((id) => t.answers[id] !== undefined).map((id) => {
      const d = this.dimension(id)!;
      return { label: d.title, value: this.optionLabel(d, t.answers[id]) };
    });
  }
}
