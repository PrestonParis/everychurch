export interface Member {
  id: number;
  churchId: number;
  displayName: string;
  joinedAt: string;
}

/** What the current user is to one church — drives which buttons and panels show. */
export interface MembershipStatus {
  member: boolean;
  staff: boolean;
  canChat: boolean;
}
