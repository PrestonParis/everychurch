import { DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  Component,
  ElementRef,
  OnInit,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { FaithAnswer, FaithCatalog } from '../../models/faith.model';
import { FaithService, faithError } from '../../services/faith.service';

@Component({
  selector: 'app-faith-quiz',
  standalone: true,
  imports: [FormsModule, RouterModule],
  templateUrl: './quiz.component.html',
  styleUrl: './faith.scss',
})
export class QuizComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  service = inject(FaithService);
  private router = inject(Router);
  @ViewChild('questionHeading') heading?: ElementRef<HTMLElement>;
  @ViewChild('explainDetails') explainDetails?: ElementRef<HTMLDetailsElement>;
  catalog = signal<FaithCatalog | null>(null);
  index = signal(0);
  answers: Record<string, FaithAnswer> = {};
  error = signal('');
  busy = signal(false);
  ngOnInit() {
    const previous = this.service.request();
    this.service
      .catalog(previous?.catalogVersion)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (c) => {
          this.catalog.set(c);
          this.answers = structuredClone(previous?.answers ?? {});
          // Resume on the question the user was last on (e.g. after an
          // accidental nav-link click away and back) rather than restarting
          // at question 1 — clamped in case the remembered index is stale
          // against this catalog's actual length.
          const resumeAt = this.service.questionIndex();
          this.index.set(Math.min(Math.max(resumeAt, 0), c.dimensions.length - 1));
        },
        error: (e) => this.error.set(faithError(e, 'quiz')),
      });
  }
  get question() {
    return this.catalog()?.dimensions[this.index()];
  }
  answer(id: string): FaithAnswer {
    return this.answers[id] ?? { option: 'unknown', importance: 1 };
  }
  choose(id: string, option: string) {
    this.answers[id] = { ...this.answer(id), option };
    this.remember();
  }
  importance(id: string, importance: number) {
    this.answers[id] = { ...this.answer(id), importance: Number(importance) };
    this.remember();
  }
  remember() {
    this.service.request.set({
      ...this.service.request(),
      catalogVersion: this.catalog()!.version,
      answers: structuredClone(this.answers),
    });
  }
  move(delta: number) {
    this.remember();
    this.index.update((i) => i + delta);
    this.service.questionIndex.set(this.index());
    // Native <details> state isn't reset by Angular's control flow (the same
    // element persists across questions, only its content changes), so an
    // explanation left open on one question would otherwise still show open
    // on the next — close it explicitly on every navigation.
    if (this.explainDetails) this.explainDetails.nativeElement.open = false;
    setTimeout(() => this.heading?.nativeElement.focus());
  }
  finish() {
    this.remember();
    this.busy.set(true);
    this.error.set('');
    this.service
      .evaluate(this.service.request()!)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.service.result.set(result);
          this.router.navigate(['/learn/denomination-quiz/results']);
        },
        error: (e) => {
          this.error.set(faithError(e, 'quiz'));
          this.busy.set(false);
        },
      });
  }
}
