import { Church } from './church.model';

export type ChurchSubmissionStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface ChurchSubmission {
  id: number;
  targetChurchId?: number | null;
  targetChurchName?: string | null;
  proposedChanges: Partial<Church>;
  submittedBy: string;
  status: ChurchSubmissionStatus;
  reviewedByEmail?: string | null;
  createdAt: string;
  reviewedAt?: string | null;
}
