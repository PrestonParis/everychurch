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
    <span class="badge">{{ statusLabel() }}</span>
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
      <div class="grid">
        <p>
          <span class="stat">{{
            match.doctrine === null ? 'Unknown' : match.doctrine + '%'
          }}</span
          ><br /><span class="muted">Doctrine · known answers</span>
        </p>
        <p>
          <span class="stat">{{
            match.practices === null ? 'Unknown' : match.practices + '%'
          }}</span
          ><br /><span class="muted">Practices · known answers</span>
        </p>
      </div>
      <p class="muted">
        {{ match.comparedTopics }} of {{ match.answeredTopics }} relevant topics
        known · {{ match.coverage }}% weighted coverage
      </p>
      <p class="muted">
        Overall alignment range: {{ match.lowerBound }}–{{ match.upperBound }} /
        100. Missing information widens this range.
      </p>
    }
    <p class="muted">
      {{
        match.kind === 'church'
          ? 'Church-confirmed profile'
          : 'Denominational reference only; local practices vary'
      }}
      @if (match.publishedAt) {
        · {{ match.publishedAt.slice(0, 10) }}
      }
    </p>
    @if (match.participation) {
      <p class="notice">
        <strong>Before you participate:</strong> {{ match.participation }}
      </p>
    }
    <details>
      <summary>Why this appears</summary>
      @for (detail of match.details; track detail.dimensionId) {
        <div style="padding:.7rem 0;border-bottom:1px solid #ddd5c9">
          <strong>{{ detail.title }}</strong>
          @if (detail.essential) {
            <span class="badge">Essential to you</span>
          }
          <p class="muted">
            You: {{ detail.userPosition }}<br />{{
              match.kind === 'church' ? 'Church' : 'Reference'
            }}: {{ detail.churchPosition }}
          </p>
          <span class="badge">{{
            detail.outcome === 'unknown'
              ? 'Not enough information'
              : detail.outcome === 'agreement'
                ? 'Agreement'
                : detail.outcome === 'partial'
                  ? 'Partial overlap — explore the distinction'
                  : 'Difference to explore'
          }}</span>
        </div>
      } @empty {
        <p>No weighted answers to compare yet.</p>
      }
    </details>
  </article>`,
})
export class MatchCardComponent {
  @Input({ required: true }) match!: FaithMatch;
  statusLabel() {
    return (
      (
        {
          strong_alignment: 'Strong alignment',
          compare: 'Compare these beliefs',
          limited_information: 'Limited information',
          needs_confirmation: 'An essential topic needs confirmation',
          essential_conflict: 'Difference on an essential topic',
          explore: 'Explore without a ranking',
        } as Record<string, string>
      )[this.match.status] ?? this.match.status
    );
  }
}
