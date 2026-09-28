import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { LearnComponent, TRADITION_FAMILIES } from './learn.component';

const catalog = {
  version: 'v1',
  rulesVersion: 'alignment-v1',
  notice: 'Test',
  dimensions: [
    { id: 'godhead', title: 'God and the Trinity', question: 'Q', explanation: 'Explains the Trinity.', category: 'doctrine', options: [{ id: 'trinity', label: 'Trinity' }], sources: [] },
    { id: 'baptism-recipients', title: 'Who is baptized', question: 'Q', explanation: 'Explains who.', category: 'doctrine', options: [{ id: 'infants', label: 'Infants and believers' }], sources: [] },
    { id: 'communion', title: 'Meaning of Communion', question: 'Q', explanation: 'Explains Communion.', category: 'doctrine', options: [{ id: 'transubstantiation', label: 'Transubstantiation' }], sources: [] },
    { id: 'governance', title: 'Church leadership', question: 'Q', explanation: 'Explains leadership.', category: 'doctrine', options: [{ id: 'pope', label: 'Pope and bishops' }], sources: [] },
    { id: 'worship', title: 'Pattern of worship', question: 'Q', explanation: 'Explains worship.', category: 'practice', options: [{ id: 'liturgical', label: 'Liturgical' }], sources: [] },
    { id: 'rosary', title: 'Rosary', question: 'Q', explanation: 'Explains the rosary.', category: 'devotion', options: [{ id: 'yes', label: 'Practiced' }], sources: [] },
    // Deliberately not in any SECTION_DEFINITIONS bucket, to exercise the "More" fallback.
    { id: 'unmapped-topic', title: 'An unmapped topic', question: 'Q', explanation: 'Explains it.', category: 'doctrine', options: [{ id: 'yes', label: 'Yes' }], sources: [] },
  ],
  traditions: [
    {
      id: 'catholic', name: 'Catholic', summary: 'Sacramental Christianity.',
      answers: { godhead: 'trinity', 'baptism-recipients': 'infants', communion: 'transubstantiation', governance: 'pope', worship: 'liturgical', rosary: 'yes', 'unmapped-topic': 'yes' },
      sources: [{ title: 'Catechism', url: 'https://example.com/catechism' }],
    },
    {
      id: 'southern-baptist', name: 'Southern Baptist', summary: 'Baptist congregations.',
      answers: { godhead: 'trinity' },
      sources: [],
    },
    {
      id: 'lcms', name: 'Lutheran — Missouri Synod', summary: 'Confessional Lutheranism.',
      answers: { godhead: 'trinity' },
      sources: [],
    },
  ],
};

describe('LearnComponent', () => {
  function setup(slug: string | null = null) {
    TestBed.configureTestingModule({
      imports: [LearnComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => slug } } } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(LearnComponent);
    fixture.detectChanges();
    http.expectOne('/api/learn/quiz').flush(catalog);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  describe('groupedTraditions (the tradition-vs-specific-church-body hierarchy)', () => {
    it('groups traditions by top-level family, keeping the more specific label per card', () => {
      const { component } = setup();
      const groups = component.groupedTraditions(catalog as any);

      expect(groups.map((g) => g.family)).toEqual(['Catholic', 'Protestant']);
      expect(groups[1].traditions.map((t) => t.id)).toEqual(['southern-baptist', 'lcms']);
      expect(component.family('southern-baptist')).toBe('Protestant · Baptist');
      expect(component.family('lcms')).toBe('Protestant · Lutheran');
    });

    it('never claims LDS as Protestant, per the project owner note on precise wording', () => {
      expect(TRADITION_FAMILIES['latter-day-saint'].family).not.toContain('Protestant');
    });
  });

  describe('sectionsFor (replaces the flat "database fields as cards" grid)', () => {
    it('groups a tradition’s answers into named sections instead of one flat list', () => {
      const { component } = setup();
      const sections = component.sectionsFor(catalog.traditions[0] as any);
      const sectionTitles = sections.map((s) => s.title);

      expect(sectionTitles).toEqual([
        'Authority & Scripture', 'Baptism', 'Communion', 'Church & ministry', 'Worship', 'Prayer & devotional life', 'More',
      ]);
      const authority = sections.find((s) => s.id === 'authority')!;
      expect(authority.entries).toEqual([
        { title: 'God and the Trinity', question: 'Q', label: 'Trinity', explanation: 'Explains the Trinity.' },
      ]);
    });

    it('puts a dimension with no home in SECTION_DEFINITIONS into a trailing "More" section rather than dropping it', () => {
      const { component } = setup();
      const sections = component.sectionsFor(catalog.traditions[0] as any);
      const more = sections.at(-1)!;
      expect(more.title).toBe('More');
      expect(more.entries.map((e) => e.title)).toContain('An unmapped topic');
    });

    it('omits a section entirely when the tradition has no answers for any of its dimensions', () => {
      const { component } = setup();
      // Southern Baptist fixture only answers "godhead" (Authority & Scripture).
      const sections = component.sectionsFor(catalog.traditions[1] as any);
      expect(sections.map((s) => s.title)).toEqual(['Authority & Scripture']);
    });
  });

  describe('quickFacts', () => {
    it('surfaces only the answered quick-fact dimensions, in the intended order', () => {
      const { component } = setup();
      const facts = component.quickFacts(catalog.traditions[0] as any);
      expect(facts).toEqual([
        { label: 'Church leadership', value: 'Pope and bishops' },
        { label: 'Who is baptized', value: 'Infants and believers' },
        { label: 'Meaning of Communion', value: 'Transubstantiation' },
        { label: 'Pattern of worship', value: 'Liturgical' },
      ]);
    });

    it('is empty rather than broken for a tradition missing every quick-fact dimension', () => {
      const { component } = setup();
      expect(component.quickFacts(catalog.traditions[1] as any)).toEqual([]);
    });
  });

  describe('denominationFilter ("Find {tradition} churches" link)', () => {
    it('maps to the curated DENOMINATIONS label the search endpoint actually matches, not the raw tradition name', () => {
      const { component } = setup();
      // "Lutheran — Missouri Synod" would match nothing server-side — this must
      // be the plain curated label instead.
      expect(component.denominationFilter('lcms')).toBe('Lutheran');
    });

    it('is null for a tradition not yet in the family/denomination map, so the template can hide the link', () => {
      const { component } = setup();
      expect(component.denominationFilter('made-up-id')).toBeNull();
    });
  });

  it('renders the tradition detail view, including its family badge, when a slug matches', () => {
    const { el } = setup('catholic');
    expect(el.textContent).toContain('Catholic');
    expect(el.textContent).toContain('Sacramental Christianity');
  });

  it('shows a clear error for a slug not in the reference library, rather than a blank page', () => {
    const { el } = setup('not-a-real-tradition');
    expect(el.textContent).toContain('not yet in the reference library');
  });
});
