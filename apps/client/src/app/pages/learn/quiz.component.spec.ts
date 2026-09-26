import { serialize, deserialize } from 'node:v8';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { QuizComponent } from './quiz.component';
import { FaithService } from '../../services/faith.service';

// jsdom does not expose the browser's structuredClone implementation.
beforeAll(() => {
  globalThis.structuredClone = <T>(value: T): T =>
    deserialize(serialize(value));
});

const catalog = {
  version: 'v1',
  rulesVersion: 'alignment-v1',
  notice: 'Test',
  traditions: [],
  dimensions: [
    {
      id: 'baptism',
      title: 'Baptism',
      category: 'doctrine',
      question: 'Who should be baptized?',
      explanation: 'A question about recipients.',
      options: [{ id: 'infants', label: 'Infants and believers' }],
      sources: [],
    },
  ],
};
describe('Denomination quiz', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [QuizComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
  });
  it('renders asynchronously, accepts uncertainty, and sends beliefs only in a POST body', async () => {
    const fixture = TestBed.createComponent(QuizComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/learn/quiz').flush(catalog);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(
      'Who should be baptized?',
    );
    fixture.componentInstance.choose('baptism', 'infants');
    fixture.componentInstance.importance('baptism', 3);
    fixture.componentInstance.finish();
    const req = http.expectOne('/api/learn/quiz/evaluate');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.keys()).toHaveLength(0);
    expect(req.request.body.answers.baptism).toEqual({
      option: 'infants',
      importance: 3,
    });
    req.flush({ error: 'retry' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Please try again');
    expect(fixture.componentInstance.busy()).toBe(false);
    http.verify();
  });
  it('keeps not-sure distinct from a belief with zero importance and can clear private session state', async () => {
    const fixture = TestBed.createComponent(QuizComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/learn/quiz').flush(catalog);
    await fixture.whenStable();
    expect(fixture.componentInstance.answer('baptism').option).toBe('unknown');
    fixture.componentInstance.choose('baptism', 'infants');
    fixture.componentInstance.importance('baptism', 0);
    expect(TestBed.inject(FaithService).request()?.answers['baptism']).toEqual({
      option: 'infants',
      importance: 0,
    });
    expect(localStorage.length).toBe(0);
    TestBed.inject(FaithService).clear();
    expect(TestBed.inject(FaithService).request()).toBeNull();
    http.verify();
  });
});
