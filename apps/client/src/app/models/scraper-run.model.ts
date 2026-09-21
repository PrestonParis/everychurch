export type ScraperRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ScraperRun {
  id: number;
  region: string | null;
  startedAt: string;
  completedAt: string | null;
  discovered: number | null;
  newCount: number | null;
  accepted: number | null;
  status: ScraperRunStatus;
}

export interface TriggerScraperRunResult {
  run: ScraperRun;
  message: string;
}

// Same 50 state codes as apps/scraper/scraper/regions.py / ScraperRunService.ALL_REGIONS.
export const US_STATE_CODES = [
  'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'FL', 'GA',
  'HI', 'ID', 'IL', 'IN', 'IA', 'KS', 'KY', 'LA', 'ME', 'MD',
  'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ',
  'NM', 'NY', 'NC', 'ND', 'OH', 'OK', 'OR', 'PA', 'RI', 'SC',
  'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV', 'WI', 'WY',
];
