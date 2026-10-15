import { Component, Input } from '@angular/core';
import { RouterModule } from '@angular/router';
import { FaithMatch } from '../../models/faith.model';

@Component({
  selector: 'app-faith-match',
  standalone: true,
  imports: [RouterModule],
  styleUrl: './faith.scss',
  styles: [
    ':host { min-height: 0; background: transparent; } .card { margin: 0; height: 100%; }',
  ],
  template: ` <article class="card">
    <h3>
      <a
        [routerLink]="
          match.kind === 'church'
            ? ['/churches', match.id, 'beliefs']
            : ['/learn/traditions', match.id]
        "
        >{{ match.name }} →</a
      >
    </h3>
    @if (match.status !== 'explore') {
      @if (match.comparedTopics > 0) {
        <p>
          <span class="stat">{{ score() }}%</span>
          <span class="muted"> match</span>
        </p>
        <p class="muted">{{ basis() }}</p>
      } @else {
        <p class="muted">
          Not enough overlap with your answers to compare yet.
        </p>
      }
      @if (note()) {
        <p class="notice">{{ note() }}</p>
      }
    }
    @if (match.kind === 'church') {
      <p class="muted">
        Beliefs confirmed by the church
        @if (match.publishedAt) {
          · {{ match.publishedAt.slice(0, 10) }}
        }
      </p>
    }
    @if (match.participation) {
      <p class="notice">
        <strong>Before you take part:</strong> {{ match.participation }}
      </p>
    }
    @if (match.details.length) {
      <details>
        <summary>See how your answers compare</summary>
        @for (detail of match.details; track detail.dimensionId) {
          <div style="padding:.7rem 0;border-bottom:1px solid #ddd5c9">
            <strong>{{ detail.title }}</strong>
            @if (detail.essential) {
              <span class="badge">Essential to you</span>
            }
            <p class="muted">
              You: {{ detail.userPosition }}<br />{{
                match.kind === 'church' ? 'This church' : 'This tradition'
              }}: {{ detail.churchPosition }}
            </p>
            <span class="badge">{{ outcomeLabel(detail.outcome) }}</span>
          </div>
        }
      </details>
    }
  </article>`,
})
export class MatchCardComponent {
  @Input({ required: true }) match!: FaithMatch;

  /** The conservative score (lowerBound): topics this tradition/church hasn't stated
   *  a position on count as not matching. It's the number results are ranked by, so
   *  the cards read in order — showing the known-answers-only doctrine % instead made
   *  a "100%" card sit above a "50%" one for reasons the page never explained. */
  score() {
    return Math.round(this.match.lowerBound);
  }

  basis() {
    const { comparedTopics: compared, answeredTopics: answered } = this.match;
    const answers = answered === 1 ? 'answer' : 'answers';
    return compared < answered
      ? `Based on ${compared} of your ${answered} ${answers}. ${this.whose()} hasn't stated a position on the rest.`
      : answered === 1
        ? 'Based on your one answer.'
        : `Based on all ${answered} of your answers.`;
  }

  note() {
    return (
      (
        {
          strong_alignment: 'A strong match on what matters to you.',
          needs_confirmation: `${this.whose()} hasn't stated a position on something you marked essential.`,
          essential_conflict: 'Differs on something you marked essential.',
        } as Record<string, string>
      )[this.match.status] ?? ''
    );
  }

  outcomeLabel(outcome: string) {
    return (
      (
        {
          agreement: 'Same view',
          partial: 'Partly the same',
          difference: 'Different view',
          unknown: 'Not stated',
        } as Record<string, string>
      )[outcome] ?? 'Different view'
    );
  }

  private whose() {
    return this.match.kind === 'church' ? 'This church' : 'This tradition';
  }
}
