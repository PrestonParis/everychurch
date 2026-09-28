import { Church } from './church.model';

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

export type ProfileVisibility = 'PUBLIC' | 'MEMBERS_ONLY' | 'PRIVATE';

/** GET /api/users/me — everything about the caller's own profile, backing Settings. */
export interface MyProfile {
  id: number;
  email: string;
  role: Role;
  displayName: string;
  avatarUrl?: string;
  username?: string;
  bio?: string;
  location?: string;
  tradition?: string;
  homeChurchId?: number;
  homeChurchName?: string;
  profileVisibility: ProfileVisibility;
  showLocation: boolean;
  showTradition: boolean;
  showHomeChurch: boolean;
  showFollowing: boolean;
}

/** PUT /api/users/me/profile — one consolidated save for the whole Profile tab. */
export interface UpdateProfileRequest {
  displayName: string;
  username?: string;
  bio?: string;
  location?: string;
  tradition?: string;
  homeChurchId?: number | null;
}

export interface UpdatePrivacyRequest {
  profileVisibility: ProfileVisibility;
  showLocation: boolean;
  showTradition: boolean;
  showHomeChurch: boolean;
  showFollowing: boolean;
}

/** GET /api/users/{username} — only the fields the viewed user has chosen to show
 *  are ever present (enforced server-side, not just hidden client-side). */
export interface PublicUserProfile {
  id: number;
  username: string;
  displayName: string;
  avatarUrl?: string;
  bio?: string;
  location?: string;
  tradition?: string;
  homeChurchId?: number;
  homeChurchName?: string;
  following?: Church[];
  isSelf: boolean;
}
