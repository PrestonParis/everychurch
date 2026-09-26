import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { FaithCatalog, FaithTradition } from '../../models/faith.model';
import { FaithService, faithError } from '../../services/faith.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-admin-faith',
  standalone: true,
  imports: [FormsModule, RouterModule],
  styleUrl: './faith.scss',
  template: ` <main class="faith-shell narrow">
    <a routerLink="/learn">← Learn</a>
    <p class="eyebrow" style="margin-top:1rem">Editorial tools</p>
    <h1>Quiz & tradition content</h1>
    @if (error()) {
      <p role="alert" class="error">{{ error() }}</p>
    }
    @if (notice()) {
      <p role="status" class="notice">{{ notice() }}</p>
    }
    @if (auth.currentUser()?.role !== 'PLATFORM_ADMIN') {
      <p>Only platform administrators can publish learning content.</p>
    } @else if (draft(); as c) {
      <p>
        Publish a new immutable edition. Existing answers and saved results keep
        their original version. Position meanings cannot be changed here; new
        theological distinctions need new stable identifiers and reviewed
        matching rules.
      </p>
      <label
        >New edition identifier<input
          [(ngModel)]="c.version"
          (ngModelChange)="confirmed = false"
          maxlength="80"
          placeholder="2026-10-v2"
      /></label>
      <label
        >Introduction<textarea
          [(ngModel)]="c.notice"
          (ngModelChange)="confirmed = false"
          maxlength="3000"
          rows="3"
        ></textarea>
      </label>
      <h2>Question wording</h2>
      @for (d of c.dimensions; track d.id) {
        <details class="card">
          <summary>{{ d.title }}</summary>
          <label
            >Question<input
              [(ngModel)]="d.question"
              (ngModelChange)="confirmed = false"
              maxlength="1000" /></label
          ><label
            >Explanation<textarea
              [(ngModel)]="d.explanation"
              (ngModelChange)="confirmed = false"
              rows="3"
              maxlength="3000"
            ></textarea>
          </label>
          <p class="muted">
            Stable answer choices:
            @for (o of d.options; track o.id) {
              {{ o.label }};
            }
          </p>
          @for (s of d.sources; track $index) {
            <label
              >Source title<input
                [(ngModel)]="s.title"
                (ngModelChange)="confirmed = false"
                maxlength="300" /></label
            ><label
              >HTTPS source URL<input
                [(ngModel)]="s.url"
                (ngModelChange)="confirmed = false"
                type="url"
                maxlength="2000"
            /></label>
          }
          <button (click)="d.sources.push({ title: '', url: '' })">
            Add source
          </button>
        </details>
      }
      <h2>Tradition references</h2>
      @for (t of c.traditions; track $index) {
        <details class="card">
          <summary>{{ t.name || 'New tradition' }}</summary>
          <label
            >Identifier<input
              [(ngModel)]="t.id"
              (ngModelChange)="confirmed = false"
              maxlength="80" /></label
          ><label
            >Name<input
              [(ngModel)]="t.name"
              (ngModelChange)="confirmed = false"
              maxlength="200" /></label
          ><label
            >Introduction<textarea
              [(ngModel)]="t.summary"
              (ngModelChange)="confirmed = false"
              rows="3"
              maxlength="3000"
            ></textarea>
          </label>
          @for (d of c.dimensions; track d.id) {
            @if (d.category !== 'devotion') {
              <label
                >{{ d.title
                }}<select
                  [ngModel]="t.answers[d.id] || ''"
                  (ngModelChange)="position(t, d.id, $event)"
                >
                  <option value="">Not specified by this reference</option>
                  @for (o of d.options; track o.id) {
                    <option [value]="o.id">{{ o.label }}</option>
                  }
                </select></label
              >
            }
          }
          @for (s of t.sources; track $index) {
            <label
              >Source title<input
                [(ngModel)]="s.title"
                (ngModelChange)="confirmed = false"
                maxlength="300" /></label
            ><label
              >HTTPS source URL<input
                [(ngModel)]="s.url"
                (ngModelChange)="confirmed = false"
                type="url"
                maxlength="2000"
            /></label>
          }
          <button (click)="t.sources.push({ title: '', url: '' })">
            Add source
          </button>
        </details>
      }
      <button
        (click)="
          c.traditions.push({
            id: '',
            name: '',
            summary: '',
            answers: {},
            sources: [{ title: '', url: '' }],
          })
        "
      >
        Add tradition reference
      </button>
      <label
        ><input type="checkbox" [(ngModel)]="confirmed" />I have reviewed this
        edition against its primary sources and checked that it fairly describes
        the represented traditions.</label
      >
      <button
        class="primary"
        [disabled]="busy() || !confirmed"
        (click)="publish()"
      >
        Publish new edition
      </button>
    }
  </main>`,
})
export class AdminFaithComponent implements OnInit {
  private service = inject(FaithService);
  private http = inject(HttpClient);
  auth = inject(AuthService);
  draft = signal<FaithCatalog | null>(null);
  error = signal('');
  notice = signal('');
  busy = signal(false);
  confirmed = false;
  ngOnInit() {
    if (this.auth.currentUser()?.role === 'PLATFORM_ADMIN')
      this.service.catalog().subscribe({
        next: (c) => this.draft.set(structuredClone(c)),
        error: (e) => this.error.set(faithError(e)),
      });
  }
  position(t: FaithTradition, id: string, value: string) {
    if (value) t.answers[id] = value;
    else delete t.answers[id];
    this.confirmed = false;
  }
  publish() {
    if (!this.confirmed) return;
    this.error.set('');
    this.busy.set(true);
    this.http
      .post<FaithCatalog>('/api/admin/faith/catalogs', this.draft())
      .subscribe({
        next: (c) => {
          this.draft.set(c);
          this.busy.set(false);
          this.confirmed = false;
          this.notice.set(
            'Published ' +
              c.version +
              '. New visitors will receive this edition.',
          );
        },
        error: (e) => {
          this.error.set(
            e.status === 409
              ? 'That edition already exists. Choose a new identifier.'
              : faithError(e),
          );
          this.busy.set(false);
        },
      });
  }
}
