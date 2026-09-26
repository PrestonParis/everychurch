export type ChurchPermission = 'EDIT_PROFILE' | 'MANAGE_SERMONS' | 'MANAGE_EVENTS' | 'MANAGE_STAFF' | 'MANAGE_POSTS' | 'MANAGE_BELIEFS';

/** One church the current user staffs, from GET /api/users/me/staff-churches. An
 *  owner's `permissions` already includes every ChurchPermission (set at grant time
 *  server-side — see ChurchStaffService.grantOwnership), so a client check never
 *  needs to special-case `isOwner` separately. */
export interface ChurchStaffSummary {
  id: number;
  churchId: number;
  userEmail: string;
  isOwner: boolean;
  permissions: ChurchPermission[];
}
