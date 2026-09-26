import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Post, MAX_POST_LENGTH } from '../../models/post.model';
import { PostService } from '../../services/post.service';

const PAGE_SIZE = 10;

/**
 * A church's posts — the content half of the follow feature (FollowButtonComponent).
 * Public to read; posting is offered to anyone the parent profile already treats as
 * able to manage the church (same broad "any staff" gate the Photos/Sermons/+Event
 * links use, not narrowed to MANAGE_POSTS specifically — the server enforces that
 * regardless, this is only about which button to show, same reasoning as those).
 */
@Component({
  selector: 'app-church-posts',
  standalone: true,
  imports: [FormsModule],
  host: { class: 'block' },
  templateUrl: './church-posts.component.html',
})
export class ChurchPostsComponent implements OnInit {
  churchId = input.required<number>();
  canManage = input<boolean>(false);

  private postService = inject(PostService);

  readonly MAX_LENGTH = MAX_POST_LENGTH;

  posts = signal<Post[]>([]);
  loading = signal(true);
  loadingOlder = signal(false);
  hasMore = signal(false);
  error = signal(false);

  draft = '';
  posting = signal(false);
  postError = signal('');

  ngOnInit() {
    this.load();
  }

  private load() {
    this.postService.list(this.churchId(), undefined, PAGE_SIZE).subscribe({
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

  loadOlder() {
    const oldest = this.posts()[this.posts().length - 1];
    if (!oldest || this.loadingOlder()) return;

    this.loadingOlder.set(true);
    this.postService.list(this.churchId(), oldest.id, PAGE_SIZE).subscribe({
      next: (batch) => {
        this.posts.set([...this.posts(), ...batch]);
        this.hasMore.set(batch.length >= PAGE_SIZE);
        this.loadingOlder.set(false);
      },
      error: () => this.loadingOlder.set(false),
    });
  }

  submit() {
    const body = this.draft.trim();
    if (!body || body.length > this.MAX_LENGTH || this.posting()) return;

    this.posting.set(true);
    this.postError.set('');
    this.postService.create(this.churchId(), body).subscribe({
      next: (post) => {
        this.posts.set([post, ...this.posts()]);
        this.draft = '';
        this.posting.set(false);
      },
      error: (err) => {
        this.posting.set(false);
        this.postError.set(err.error?.error ?? 'Could not post. Please try again.');
      },
    });
  }

  delete(post: Post) {
    if (!confirm('Delete this post?')) return;
    this.postService.delete(this.churchId(), post.id).subscribe({
      next: () => this.posts.set(this.posts().filter((p) => p.id !== post.id)),
    });
  }

  formatDate(iso: string): string {
    const date = new Date(iso);
    return date.toDateString() === new Date().toDateString()
      ? date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
      : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }
}
