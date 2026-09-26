export type ReportTargetType = 'USER' | 'CHURCH';
export type ReportStatus = 'OPEN' | 'ACTIONED' | 'DISMISSED';

/** Matches server/.../dto/ReportDto.java. */
export interface Report {
  id: number;
  reporterId: number;
  reporterDisplayName: string;
  targetType: ReportTargetType;
  targetId: number;
  targetLabel: string;
  reason: string;
  status: ReportStatus;
  createdAt: string;
  resolvedAt?: string;
  resolvedByDisplayName?: string;
}
