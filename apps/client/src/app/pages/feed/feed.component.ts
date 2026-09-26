import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Post } from '../../models/post.model';
import { PostService } from '../../services/post.service';

const PAGE_SIZE = 20;

/** Chronological posts from every church the current user follows — the "church
 *  social media" timeline (see FollowButtonComponent / ChurchPostsComponent for
 *  the follow/post halves of the feature). */
@Component({
  selector: 'app-feed',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './feed.component.html',
})
export class FeedComponent implements OnInit {
  private postService = inject(PostService);

  posts = signal<Post[]>([]);
  loading = signal(true);
  loadingMore = signal(false);
  hasMore = signal(false);
  error = signal(false);

  ngOnInit() {
    this.postService.feed(undefined, PAGE_SIZE).subscribe({
      next: (posts) => {
        this.posts.set(posts);
        this.hasMore.set(posts.length >= PAGE_SIZE);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  loadMore() {
    const oldest = this.posts()[this.posts().length - 1];
    if (!oldest || this.loadingMore()) return;

    this.loadingMore.set(true);
    this.postService.feed(oldest.id, PAGE_SIZE).subscribe({
      next: (batch) => {
        this.posts.set([...this.posts(), ...batch]);
        this.hasMore.set(batch.length >= PAGE_SIZE);
        this.loadingMore.set(false);
      },
      error: () => this.loadingMore.set(false),
    });
  }

  formatDate(iso: string): string {
    const date = new Date(iso);
    return date.toDateString() === new Date().toDateString()
      ? date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
      : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }
}
