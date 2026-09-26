/** Matches server/.../dto/PostDto.java. */
export interface Post {
  id: number;
  churchId: number;
  churchName: string;
  authorId: number;
  authorDisplayName: string;
  body: string;
  imageUrl?: string;
  createdAt: string;
}

/** Mirrors PostService.MAX_BODY_LENGTH on the server. */
export const MAX_POST_LENGTH = 5000;
