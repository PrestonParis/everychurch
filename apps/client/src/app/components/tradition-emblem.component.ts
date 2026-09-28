import { Component, computed, input } from '@angular/core';
import { denomStyle, tintColor } from '../utils/church-display.util';

/**
 * The one place the 14 tradition emblems are drawn — previously this SVG @switch was
 * copy-pasted three times across church-list.component.html (browse tiles, the active
 * "Browsing: X" pill, and every card) with a fourth copy's worth of duplication avoided
 * only by church-profile.component.html not showing an icon at all. Centralizing it here
 * is what makes it practical to reuse across Browse, church profiles, and anywhere else
 * a tradition needs to read as the same identity (see docs on denomStyle in
 * utils/church-display.util.ts for why the icon set itself changed from generic
 * cross/flame/book reuse to one distinct emblem per tradition).
 *
 * Visual language: a pale tint of the tradition's own color as a circular medallion
 * background, with the emblem stroked *in* that color — never a solid color tile with a
 * white icon. That's a deliberate shift from "looks like an app icon" to "reads like an
 * heraldic/seal mark," matching the site's serif, ecclesiastical tone. `selected` bumps
 * the tint and adds a ring for filter/chip contexts; plain usage (cards, profile hero)
 * leaves it off.
 */
@Component({
  selector: 'app-tradition-emblem',
  standalone: true,
  // inline-flex on the host itself (not just the template's inner <span>) so hover
  // transforms/centering applied by a parent (e.g. the card header's group-hover
  // scale/rotate) act on a proper box rather than the browser's default inline display.
  host: { class: 'inline-flex' },
  templateUrl: './tradition-emblem.component.html',
})
export class TraditionEmblemComponent {
  readonly denomination = input<string | undefined>();
  /** Medallion diameter in px. 28-32 suits filter tiles/cards; smaller (18-20) suits inline pills. */
  readonly size = input(32);
  readonly selected = input(false);

  readonly style = computed(() => denomStyle(this.denomination()));
  readonly emblemSize = computed(() => Math.round(this.size() * 0.56));
  readonly background = computed(() => tintColor(this.style().color, this.selected() ? 0.2 : 0.1));
}
