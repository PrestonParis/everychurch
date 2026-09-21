// Named ChurchEvent, not Event, to avoid shadowing the DOM's global Event type.
export interface ChurchEvent {
  id?: number;
  title: string;
  description?: string;
  startsAt: string; // ISO instant, e.g. from <input type="datetime-local"> via toISOString()
  endsAt?: string;
  churchIds: number[];
}
