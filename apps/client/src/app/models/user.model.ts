export type Role = 'MEMBER' | 'VOLUNTEER' | 'CHURCH_ADMIN' | 'PLATFORM_ADMIN';

export interface AuthResponse {
  token: string;
  email: string;
  role: Role;
}
