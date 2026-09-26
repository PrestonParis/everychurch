import { Injectable, Signal, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Client } from '@stomp/stompjs';
import { Observable, Subject } from 'rxjs';
import { ConversationSummary, DirectMessage } from '../models/direct-message.model';
import { ChatConnectionState } from '../models/chat.model';
import { STOMP_CLIENT_FACTORY } from './chat.service';
import { AuthService } from './auth.service';

/** One live connection to a single conversation. Close it when the thread view goes away. */
export interface DirectChatConnection {
  messages$: Observable<DirectMessage>;
  state: Signal<ChatConnectionState>;
  send(body: string): void;
  close(): void;
}

/** Same shape as ChatService, keyed by the other user's id instead of a church —
 *  see that class for the reasoning behind each STOMP detail (raw WebSocket
 *  transport, re-reading the token on every reconnect, etc.), not repeated here. */
@Injectable({ providedIn: 'root' })
export class DirectMessageService {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  private clientFactory = inject(STOMP_CLIENT_FACTORY);

  conversations(): Observable<ConversationSummary[]> {
    return this.http.get<ConversationSummary[]>('/api/users/me/conversations');
  }

  history(otherUserId: number, before?: number, limit?: number): Observable<DirectMessage[]> {
    let params = new HttpParams();
    if (before !== undefined) params = params.set('before', before);
    if (limit !== undefined) params = params.set('limit', limit);
    return this.http.get<DirectMessage[]>(`/api/direct-messages/${otherUserId}`, { params });
  }

  connect(otherUserId: number): DirectChatConnection {
    const state = signal<ChatConnectionState>('connecting');
    const messages = new Subject<DirectMessage>();
    const destination = `/app/direct-messages/${otherUserId}/send`;
    const myId = this.auth.currentUser()?.id;

    const client: Client = this.clientFactory({
      brokerURL: this.brokerUrl(),
      reconnectDelay: 5000,
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${this.auth.getToken() ?? ''}` };
        state.set('connecting');
      },
      onConnect: () => {
        state.set('connected');
        if (myId !== undefined) {
          // Same canonical "lower id first" rule as DirectMessageService.topicFor on
          // the server — StompAuthChannelInterceptor only accepts a SUBSCRIBE for
          // that exact form.
          const lo = Math.min(myId, otherUserId);
          const hi = Math.max(myId, otherUserId);
          client.subscribe(`/topic/direct-messages/${lo}-${hi}`, (frame) => {
            try {
              messages.next(JSON.parse(frame.body) as DirectMessage);
            } catch {
              // a frame that isn't valid message JSON isn't worth crashing the panel over
            }
          });
        }
      },
      onWebSocketClose: () => {
        if (state() !== 'error') state.set('disconnected');
      },
      onStompError: () => {
        state.set('error');
        void client.deactivate();
      },
    });

    client.activate();

    return {
      messages$: messages.asObservable(),
      state: state.asReadonly(),
      send: (body: string) => {
        const trimmed = body.trim();
        if (!trimmed || !client.connected) return;
        client.publish({
          destination,
          body: JSON.stringify({ body: trimmed }),
          headers: { 'content-type': 'application/json' },
        });
      },
      close: () => {
        messages.complete();
        void client.deactivate();
      },
    };
  }

  private brokerUrl(): string {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${scheme}://${window.location.host}/ws/websocket`;
  }
}
