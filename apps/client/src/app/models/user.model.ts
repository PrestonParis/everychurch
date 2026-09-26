export type Role = 'MEMBER' | 'VOLUNTEER' | 'CHURCH_ADMIN' | 'PLATFORM_ADMIN';

export interface AuthResponse {
  token: string;
  id: number;
  email: string;
  role: Role;
  displayName: string;
  avatarUrl?: string;
}

/** Shape returned by the display-name/avatar update endpoints — mirrors AuthResponse minus the token. */
export interface UserProfile {
  id: number;
  email: string;
  role: Role;
  displayName: string;
  avatarUrl?: string;
}
