import { serialize, deserialize } from 'node:v8';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { BeliefsComponent } from './beliefs.component';

// jsdom does not expose the browser's structuredClone implementation.
beforeAll(() => {
  globalThis.structuredClone = <T>(value: T): T =>
    deserialize(serialize(value));
});

const c = {
  version: 'v1',
  rulesVersion: 'alignment-v1',
  notice: '',
  dimensions: [
    {
      id: 'godhead',
      title: 'God',
      question: 'God?',
      explanation: '',
      category: 'doctrine',
      options: [{ id: 'trinity', label: 'Trinity' }],
      sources: [],
    },
  ],
  traditions: [
    {
      id: 'example',
      name: 'Example',
      summary: '',
      answers: { godhead: 'trinity' },
      sources: [{ title: 'Source', url: 'https://example.org/faith' }],
    },
  ],
};
describe('Church belief editing', () => {
  it('does not publish templates without review and preserves existing church answers', async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [BeliefsComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => '5' }, data: { editing: true } },
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(BeliefsComponent),
      http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/churches/5/beliefs/editor').flush({
      draft: { content: null },
      published: {
        churchId: 5,
        churchName: 'Example',
        content: null,
        revision: null,
      },
      history: [],
    });
    http.expectOne('/api/learn/quiz').flush(c);
    await fixture.whenStable();
    const component = fixture.componentInstance;
    component.input.traditionId = 'example';
    component.template();
    expect(component.value('godhead')).toBe('trinity');
    component.change('godhead', 'option', 'multiple');
    component.template();
    expect(component.value('godhead')).toBe('multiple');
    component.save(true);
    expect(component.error()).toContain('Confirm');
    http.expectNone('/api/churches/5/beliefs/publish');
    component.save(false);
    const req = http.expectOne('/api/churches/5/beliefs/draft');
    expect(req.request.method).toBe('PUT');
    req.flush({});
    await fixture.whenStable();
    expect(component.notice()).toContain('public profile has not changed');
    http.verify();
  });
});
