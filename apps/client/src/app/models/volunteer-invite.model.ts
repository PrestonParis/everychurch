export type VolunteerInviteStatus = 'PENDING' | 'REDEEMED' | 'REVOKED';

export interface VolunteerInvite {
  id: number;
  code: string;
  status: VolunteerInviteStatus;
  createdAt: string;
  expiresAt?: string | null;
  redeemedAt?: string | null;
}
