export interface ChurchRef {
  id: number;
  name: string;
}

// Named ChurchEvent, not Event, to avoid shadowing the DOM's global Event type.
export interface ChurchEvent {
  id?: number;
  title: string;
  description?: string;
  startsAt: string; // ISO instant, e.g. from <input type="datetime-local"> via toISOString()
  endsAt?: string;
  // CONFIRMED + PENDING co-host ids (not DECLINED) — what the edit form preselects.
  churchIds: number[];
  /** CONFIRMED only — id+name for each public co-host, so a card can show "Hosted by
   *  X" instead of just a count. A church here has actually agreed to co-host; see
   *  pendingChurches for ones only invited so far (EventCoHost's consent workflow,
   *  found missing in review). */
  churches?: ChurchRef[];
  /** PENDING only — invited but not yet responded. Shown to the event's managers and
   *  to the invited church's own staff, never as a public "hosted by" claim. */
  pendingChurches?: ChurchRef[];
}
