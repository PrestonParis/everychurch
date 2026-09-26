import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ChatConnectionState } from '../../models/chat.model';
import { DirectMessage, MAX_DIRECT_MESSAGE_LENGTH, mergeDirectMessages } from '../../models/direct-message.model';
import { AuthService } from '../../services/auth.service';
import { DirectChatConnection, DirectMessageService } from '../../services/direct-message.service';
import { BlockService } from '../../services/block.service';
import { ReportButtonComponent } from '../../components/report-button.component';

const PAGE_SIZE = 50;

/**
 * One 1:1 conversation — same live+history-merge shape as ChurchChatComponent (see
 * that component for the reasoning behind the reconnect/merge/scroll-follow details,
 * not repeated here), just keyed by the other user's id instead of a church.
 */
@Component({
  selector: 'app-message-thread',
  standalone: true,
  imports: [FormsModule, RouterModule, ReportButtonComponent],
  templateUrl: './message-thread.component.html',
})
export class MessageThreadComponent implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private directMessageService = inject(DirectMessageService);
  private blockService = inject(BlockService);
  private authService = inject(AuthService);

  blocked = signal(false);
  blockBusy = signal(false);

  toggleBlock() {
    if (this.blockBusy()) return;
    this.blockBusy.set(true);
    const wasBlocked = this.blocked();
    const obs = wasBlocked ? this.blockService.unblock(this.otherUserId) : this.blockService.block(this.otherUserId);
    obs.subscribe({
      next: () => {
        this.blocked.set(!wasBlocked);
        this.blockBusy.set(false);
      },
      error: () => this.blockBusy.set(false),
    });
  }

  @ViewChild('scroller') scroller?: ElementRef<HTMLElement>;

  readonly MAX_LENGTH = MAX_DIRECT_MESSAGE_LENGTH;

  otherUserId!: number;
  // The conversation list is the only place that knows the other person's name; a
  // fresh page load (e.g. a shared link, or a refresh) has nothing to show until the
  // first message arrives. Backfilled from the first history page's senderDisplayName
  // once it loads, same as the fallback every other page in this app uses for a name
  // it doesn't have yet.
  otherDisplayName = signal('');

  messages = signal<DirectMessage[]>([]);
  draft = '';
  loading = signal(true);
  loadingOlder = signal(false);
  hasMore = signal(false);
  historyError = signal(false);

  private connection = signal<DirectChatConnection | null>(null);
  connectionState = computed<ChatConnectionState>(() => this.connection()?.state() ?? 'connecting');

  private subscription = new Subscription();
  private stickToBottom = true;

  constructor() {
    effect(() => {
      if (this.connectionState() === 'connected') {
        untracked(() => this.loadLatest());
      }
    });
  }

  ngOnInit() {
    this.otherUserId = Number(this.route.snapshot.paramMap.get('userId'));
    this.loadLatest();

    this.blockService.status(this.otherUserId).subscribe({
      next: (status) => this.blocked.set(status.blocked),
      error: () => {},
    });

    const connection = this.directMessageService.connect(this.otherUserId);
    this.connection.set(connection);
    this.subscription.add(
      connection.messages$.subscribe((message) => {
        this.messages.set(mergeDirectMessages(this.messages(), [message]));
        if (!this.isMine(message)) this.otherDisplayName.set(message.senderDisplayName);
        this.scrollToBottomIfFollowing();
      })
    );
  }

  ngOnDestroy() {
    this.subscription.unsubscribe();
    this.connection()?.close();
  }

  private loadLatest() {
    const wasEmpty = this.messages().length === 0;
    this.directMessageService.history(this.otherUserId, undefined, PAGE_SIZE).subscribe({
      next: (batch) => {
        this.messages.set(mergeDirectMessages(this.messages(), batch));
        const fromOther = batch.find((m) => !this.isMine(m));
        if (fromOther) this.otherDisplayName.set(fromOther.senderDisplayName);
        if (wasEmpty) this.hasMore.set(batch.length >= PAGE_SIZE);
        this.loading.set(false);
        this.historyError.set(false);
        this.scrollToBottomIfFollowing();
      },
      error: () => {
        this.loading.set(false);
        this.historyError.set(true);
      },
    });
  }

  loadOlder() {
    const oldest = this.messages()[0];
    if (!oldest || this.loadingOlder()) return;

    this.loadingOlder.set(true);
    const el = this.scroller?.nativeElement;
    const heightBefore = el?.scrollHeight ?? 0;

    this.directMessageService.history(this.otherUserId, oldest.id, PAGE_SIZE).subscribe({
      next: (batch) => {
        this.messages.set(mergeDirectMessages(this.messages(), batch));
        this.hasMore.set(batch.length >= PAGE_SIZE);
        this.loadingOlder.set(false);
        setTimeout(() => {
          if (el) el.scrollTop += el.scrollHeight - heightBefore;
        });
      },
      error: () => this.loadingOlder.set(false),
    });
  }

  send() {
    const body = this.draft.trim();
    if (!body || body.length > this.MAX_LENGTH || this.connectionState() !== 'connected' || this.blocked()) return;
    this.connection()?.send(body);
    this.draft = '';
    this.stickToBottom = true;
  }

  onKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  onScroll() {
    const el = this.scroller?.nativeElement;
    if (!el) return;
    this.stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  }

  private scrollToBottomIfFollowing() {
    if (!this.stickToBottom) return;
    setTimeout(() => {
      const el = this.scroller?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }

  isMine(message: DirectMessage): boolean {
    return message.senderId === this.authService.currentUser()?.id;
  }

  formatTime(iso: string): string {
    const date = new Date(iso);
    const time = date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
    return date.toDateString() === new Date().toDateString()
      ? time
      : `${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}, ${time}`;
  }
}
