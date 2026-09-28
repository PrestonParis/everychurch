/**
 * Curated per-tradition emblem + color — a small ecclesiastical identity system, not a
 * generic icon set. Each of the 14 DENOMINATIONS labels (church.model.ts) gets its own
 * historically/theologically distinct emblem (e.g. a chalice for Church of Christ, a
 * Luther rose for Lutheran, a three-bar cross for Orthodox) rather than a handful of
 * generic shapes (cross/flame/book) reused across unrelated traditions — the previous
 * version had Catholic, Lutheran and Presbyterian all sharing a plain 'cross', and
 * Evangelical/Methodist/Pentecostal all sharing 'flame', which is exactly the kind of
 * "looks religious" placeholder duplication that makes traditions hard to tell apart at
 * a glance. Bulk-imported/scraped data stores denomination as raw abbreviations ("SBC",
 * "NBC", "COC") rather than these curated labels, so classification is keyword/substring
 * based to catch both forms. Shared by the church directory (browse tiles, filter chip,
 * cards) and the church profile hero so the same tradition always reads as the same
 * emblem + color everywhere in the app.
 */
export type DenomIcon =
  | 'openBookWave' // Baptist — open Bible + water motif (baptism)
  | 'crossPlain' // Catholic — plain Latin cross
  | 'chalice' // Church of Christ — communion cup
  | 'crossThreeBar' // Eastern Orthodox — three-bar Orthodox cross
  | 'crossPotent' // Episcopal/Anglican — cross with small terminal bars (Canterbury-style)
  | 'crossRadiant' // Evangelical — cross with radiating rays
  | 'temple' // Latter-day Saint — temple silhouette with a spire
  | 'lutherRose' // Lutheran — simplified Luther Rose (cross-in-heart-in-rose)
  | 'crossFlame' // Methodist — cross flanked by twin flames
  | 'chapel' // Non-denominational — plain gathering-place silhouette, no spire
  | 'flame' // Pentecostal / Assembly of God — a single flame
  | 'crossCeltic' // Presbyterian — cross with a Celtic/reformed ring
  | 'openBookRays' // Seventh-day Adventist — open Bible + radiant light
  | 'compassStar'; // Other — neutral four-point star (echoes the site's own compass mark)

const DENOM_STYLES: Record<string, { color: string; icon: DenomIcon }> = {
  catholic: { color: '#7A2331', icon: 'crossPlain' },
  baptist: { color: '#1F5C57', icon: 'openBookWave' },
  churchOfChrist: { color: '#3E4C5C', icon: 'chalice' },
  orthodox: { color: '#5B3159', icon: 'crossThreeBar' },
  episcopal: { color: '#2E4057', icon: 'crossPotent' },
  evangelical: { color: '#A4542E', icon: 'crossRadiant' },
  lds: { color: '#7A5C1E', icon: 'temple' },
  lutheran: { color: '#5B3A5C', icon: 'lutherRose' },
  methodist: { color: '#B8792E', icon: 'crossFlame' },
  nonDenom: { color: '#6B6258', icon: 'chapel' },
  pentecostal: { color: '#B5451F', icon: 'flame' },
  presbyterian: { color: '#3F6B4A', icon: 'crossCeltic' },
  adventist: { color: '#2E6B5E', icon: 'openBookRays' },
  other: { color: '#8C8478', icon: 'compassStar' },
};

export function denomStyle(denomination?: string): { color: string; icon: DenomIcon } {
  const d = (denomination ?? '').toLowerCase();
  const key =
    (d.includes('cath') && 'catholic') ||
    ((d.includes('bapt') || d.includes('sbc') || d.includes('nbc')) && 'baptist') ||
    (d.includes('coc') && 'churchOfChrist') ||
    (d.includes('orthodox') && 'orthodox') ||
    ((d.includes('episcop') || d.includes('anglic')) && 'episcopal') ||
    (d.includes('evangel') && 'evangelical') ||
    ((d.includes('latter-day') || d.includes('lds') || d.includes('mormon')) && 'lds') ||
    (d.includes('luther') && 'lutheran') ||
    (d.includes('method') && 'methodist') ||
    ((d.includes('non-denom') || d.includes('nondenom')) && 'nonDenom') ||
    ((d.includes('pentecost') || d.includes('assembl')) && 'pentecostal') ||
    (d.includes('presbyter') && 'presbyterian') ||
    ((d.includes('advent') || d === 'sda') && 'adventist') ||
    'other';
  return DENOM_STYLES[key];
}

/** Tints a curated hex color for a badge chip's background (e.g. 14% alpha). */
export function tintColor(hex: string, alpha: number): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/** Bulk-imported church names are stored upper-cased ("SAINT JOSEPH CHURCH") — this
 *  displays them in Title Case so they read like a directory entry rather than a
 *  printed registry. Apostrophe is deliberately NOT a word-boundary trigger here
 *  (unlike whitespace/./-) — "ST. MARY'S CATHOLIC CHURCH" should become "St. Mary's
 *  Catholic Church", not "St. Mary'S..."; possessives/contractions (the vast
 *  majority of apostrophes in real church names) need the letter after the
 *  apostrophe to stay lowercase. This does mean a genuine name like O'BRIEN comes
 *  out "O'brien" rather than "O'Brien" — a real but much rarer case in this data,
 *  and a strictly better trade than breaking every possessive. */
export function titleCase(s: string): string {
  return s.toLowerCase().replace(/(^|[\s.-])([a-z])/g, (_, sep, ch) => sep + ch.toUpperCase());
}
