export type ClaimStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'RELEASED';

/** Matches server/.../dto/ClaimDto.java. */
export interface Claim {
  id: number;
  churchId: number;
  churchName: string;
  requesterEmail: string;
  status: ClaimStatus;
  notes?: string | null;
  decidedByEmail?: string | null;
  createdAt: string;
  decidedAt?: string | null;
}
