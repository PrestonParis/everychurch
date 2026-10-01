import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FaithMatch } from '../../models/faith.model';
import { MatchCardComponent } from './match-card.component';

function match(overrides: Partial<FaithMatch> = {}): FaithMatch {
  return {
    id: 'united-methodist',
    name: 'United Methodist',
    kind: 'tradition',
    status: 'limited_information',
    doctrine: 100,
    practices: null,
    coverage: 66.7,
    lowerBound: 66.7,
    upperBound: 100,
    answeredTopics: 3,
    comparedTopics: 2,
    profileRevision: null,
    publishedAt: null,
    participation: null,
    details: [
      {
        dimensionId: 'godhead',
        title: 'God and the Trinity',
        essential: true,
        userPosition: 'Trinity',
        churchPosition: 'Trinity',
        outcome: 'agreement',
      },
      {
        dimensionId: 'communion',
        title: 'Meaning of Communion',
        essential: false,
        userPosition: 'Memorial',
        churchPosition: 'Not stated',
        outcome: 'unknown',
      },
    ],
    ...overrides,
  } as FaithMatch;
}

async function render(m: FaithMatch) {
  await TestBed.configureTestingModule({
    imports: [MatchCardComponent],
    providers: [provideRouter([])],
  }).compileComponents();
  const fixture = TestBed.createComponent(MatchCardComponent);
  fixture.componentRef.setInput('match', m);
  await fixture.whenStable();
  return (fixture.nativeElement as HTMLElement).textContent ?? '';
}

describe('MatchCardComponent', () => {
  it('shows one rounded score — the conservative one results are ranked by', async () => {
    const text = await render(match());

    expect(text).toContain('67%');
    expect(text).not.toContain('100%');
  });

  it('says how many answers the score is based on, in plain words', async () => {
    const text = await render(match());

    expect(text).toContain(
      "Based on 2 of your 3 answers. This tradition hasn't stated a position on the rest.",
    );
  });

  it('says "all" when every answer was compared', async () => {
    const text = await render(match({ comparedTopics: 3, lowerBound: 33.3 }));

    expect(text).toContain('Based on all 3 of your answers.');
  });

  it('uses the singular for a single answer', async () => {
    const text = await render(match({ answeredTopics: 1, comparedTopics: 1 }));

    expect(text).toContain('Based on your one answer.');
  });

  it('drops the jargon the old card showed', async () => {
    const text = await render(match());

    for (const phrase of ['weighted coverage', 'alignment range', 'known answers', 'Unknown', 'Limited information']) {
      expect(text).not.toContain(phrase);
    }
  });

  it('shows no score when nothing could be compared', async () => {
    const text = await render(match({ comparedTopics: 0, lowerBound: 0 }));

    expect(text).toContain('Not enough overlap with your answers to compare yet.');
    expect(text).not.toContain('%');
  });

  it('shows no score or basis at all in explore mode', async () => {
    const text = await render(match({ status: 'explore' }));

    expect(text).not.toContain('%');
    expect(text).not.toContain('Based on');
  });

  it('flags an essential-topic difference plainly', async () => {
    const text = await render(match({ status: 'essential_conflict' }));

    expect(text).toContain('Differs on something you marked essential.');
  });

  it('flags an unstated essential topic, naming a church as a church', async () => {
    const text = await render(match({ kind: 'church', status: 'needs_confirmation', publishedAt: '2026-09-01T00:00:00Z' }));

    expect(text).toContain("This church hasn't stated a position on something you marked essential.");
    expect(text).toContain('Beliefs confirmed by the church');
    expect(text).toContain('2026-09-01');
  });

  it('labels a strong match', async () => {
    expect(await render(match({ status: 'strong_alignment' }))).toContain('A strong match on what matters to you.');
  });

  it('labels each topic comparison in plain words', async () => {
    const text = await render(match());

    expect(text).toContain('See how your answers compare');
    expect(text).toContain('Same view');
    expect(text).toContain('Not stated');
    expect(text).toContain('This tradition: Trinity');
  });

  it('labels partial and different views', () => {
    const card = new MatchCardComponent();
    expect(card.outcomeLabel('partial')).toBe('Partly the same');
    expect(card.outcomeLabel('difference')).toBe('Different view');
  });

  it('omits the comparison list when there is nothing to list', async () => {
    expect(await render(match({ details: [] }))).not.toContain('See how your answers compare');
  });

  it('shows participation guidance', async () => {
    const text = await render(match({ kind: 'church', participation: 'Communion is for baptized members.' }));

    expect(text).toContain('Before you take part:');
    expect(text).toContain('Communion is for baptized members.');
  });
});
