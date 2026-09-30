/** GET /api/import-status — matches server/.../dto/ImportStatusDto.java. Polled
 *  (not cached) by the church-list cold-start onboarding screen, so each call must
 *  return a fresh value — unlike PublicConfig, this isn't a shareReplay candidate. */
export interface ImportStatus {
  churchCount: number;
  importRunning: boolean;
  totalRows?: number;
  processedRows: number;
  failed: boolean;
}
