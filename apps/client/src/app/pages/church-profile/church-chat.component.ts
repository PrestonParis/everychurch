import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ChatConnectionState, ChatMessage, MAX_CHAT_MESSAGE_LENGTH, mergeMessages } from '../../models/chat.model';
import { AuthService } from '../../services/auth.service';
import { ChatConnection, ChatService } from '../../services/chat.service';

const PAGE_SIZE = 50;

/**
 * The group chat panel for one church. History comes over REST (paged backwards with a
 * cursor); new messages arrive live over the STOMP socket and are merged in by id, so a
 * message that shows up both live and in a post-reconnect history reload appears once.
 * Only rendered for people the server lets into the chat (see ChurchCommunityComponent).
 */
@Component({
  selector: 'app-church-chat',
  standalone: true,
  imports: [FormsModule],
  host: { class: 'block' },
  templateUrl: './church-chat.component.html',
})
export class ChurchChatComponent implements OnInit, OnDestroy {
  churchId = input.required<number>();

  private chatService = inject(ChatService);
  private authService = inject(AuthService);

  @ViewChild('scroller') scroller?: ElementRef<HTMLElement>;

  readonly MAX_LENGTH = MAX_CHAT_MESSAGE_LENGTH;

  messages = signal<ChatMessage[]>([]);
  draft = '';
  loading = signal(true);
  loadingOlder = signal(false);
  hasMore = signal(false);
  historyError = signal(false);

  private connection = signal<ChatConnection | null>(null);
  connectionState = computed<ChatConnectionState>(() => this.connection()?.state() ?? 'connecting');

  private subscription = new Subscription();
  /** Follow new messages only while the reader is already at the bottom. */
  private stickToBottom = true;

  constructor() {
    // Every (re)connect reloads the latest page: anything sent while we were offline —
    // or in the gap between the first history load and the subscription going live —
    // is picked up, and mergeMessages makes the overlap harmless.
    effect(() => {
      if (this.connectionState() === 'connected') {
        untracked(() => this.loadLatest());
      }
    });
  }

  ngOnInit() {
    this.loadLatest();

    const connection = this.chatService.connect(this.churchId());
    this.connection.set(connection);
    this.subscription.add(
      connection.messages$.subscribe((message) => {
        this.messages.set(mergeMessages(this.messages(), [message]));
        this.scrollToBottomIfFollowing();
      })
    );
  }

  ngOnDestroy() {
    this.subscription.unsubscribe();
    this.connection()?.close();
  }

  /** Must match what the server puts in MessageDto.senderDisplayName, or "is this mine?" styling breaks. */
  get myName(): string {
    return this.authService.currentUser()?.displayName ?? '';
  }

  private loadLatest() {
    const wasEmpty = this.messages().length === 0;
    this.chatService.history(this.churchId(), undefined, PAGE_SIZE).subscribe({
      next: (batch) => {
        this.messages.set(mergeMessages(this.messages(), batch));
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

    this.chatService.history(this.churchId(), oldest.id, PAGE_SIZE).subscribe({
      next: (batch) => {
        this.messages.set(mergeMessages(this.messages(), batch));
        this.hasMore.set(batch.length >= PAGE_SIZE);
        this.loadingOlder.set(false);
        // Prepending pushes everything down; keep the reader looking at the same message.
        setTimeout(() => {
          if (el) el.scrollTop += el.scrollHeight - heightBefore;
        });
      },
      error: () => this.loadingOlder.set(false),
    });
  }

  send() {
    const body = this.draft.trim();
    if (!body || body.length > this.MAX_LENGTH || this.connectionState() !== 'connected') return;
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

  isMine(message: ChatMessage): boolean {
    return message.senderDisplayName === this.myName;
  }

  formatTime(iso: string): string {
    const date = new Date(iso);
    const time = date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
    return date.toDateString() === new Date().toDateString()
      ? time
      : `${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}, ${time}`;
  }
}
