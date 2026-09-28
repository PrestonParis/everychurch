import { Injectable, InjectionToken, Signal, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Client, StompConfig } from '@stomp/stompjs';
import { Observable, Subject } from 'rxjs';
import { ChatConnectionState, ChatMessage } from '../models/chat.model';
import { AuthService } from './auth.service';

/** How ChatService builds its STOMP client — swapped for a fake in tests. */
export const STOMP_CLIENT_FACTORY = new InjectionToken<(config: StompConfig) => Client>('STOMP_CLIENT_FACTORY', {
  providedIn: 'root',
  factory: () => (config) => new Client(config),
});

/** One live connection to a single church's chat. Close it when the chat view goes away. */
export interface ChatConnection {
  messages$: Observable<ChatMessage>;
  state: Signal<ChatConnectionState>;
  send(body: string): void;
  close(): void;
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  private clientFactory = inject(STOMP_CLIENT_FACTORY);

  /** A page of history, oldest first. `before` is the id of the oldest message already loaded. */
  history(churchId: number, before?: number, limit?: number): Observable<ChatMessage[]> {
    let params = new HttpParams();
    if (before !== undefined) params = params.set('before', before);
    if (limit !== undefined) params = params.set('limit', limit);
    return this.http.get<ChatMessage[]>(`/api/churches/${churchId}/messages`, { params });
  }

  /**
   * Opens the live socket. The JWT goes on the STOMP CONNECT frame rather than an HTTP
   * header because a browser won't attach one to a WebSocket handshake — the server's
   * StompAuthChannelInterceptor reads it from there.
   *
   * Uses the raw WebSocket transport (/ws/websocket) rather than SockJS: every supported
   * browser has WebSocket, and skipping sockjs-client avoids a legacy CommonJS dependency
   * that needs a `global` shim under the esbuild-based Angular builder. The server still
   * serves SockJS at /ws if a fallback is ever needed.
   */
  connect(churchId: number): ChatConnection {
    const state = signal<ChatConnectionState>('connecting');
    const messages = new Subject<ChatMessage>();
    const destination = `/app/churches/${churchId}/chat.send`;

    const client: Client = this.clientFactory({
      brokerURL: this.brokerUrl(),
      reconnectDelay: 5000,
      // Re-read the token on every (re)connect attempt so a reconnect after logging in
      // again doesn't reuse a stale one.
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${this.auth.getToken() ?? ''}` };
        state.set('connecting');
      },
      onConnect: () => {
        state.set('connected');
        client.subscribe(`/topic/churches/${churchId}/chat`, (frame) => {
          try {
            messages.next(JSON.parse(frame.body) as ChatMessage);
          } catch {
            // a frame that isn't valid chat JSON isn't worth crashing the panel over
          }
        });
      },
      onWebSocketClose: () => {
        if (state() !== 'error') state.set('disconnected');
      },
      // The server answers a refused token/subscription with an ERROR frame and closes
      // the socket. Retrying the same rejected credentials forever would just spin, so
      // stop and surface it.
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

  // Vercel serves the client and proxies /api/** to the Cloud Run API via a
  // vercel.json rewrite (an ordinary HTTP request/response, which it handles
  // fine) — but confirmed live that it does NOT proxy a raw WebSocket upgrade
  // the same way (the connection fails outright). So on the deployed
  // domains specifically, this connects straight to the API's own host,
  // bypassing Vercel for just this one connection.
  //
  // TODO: revisit if the API gets its own subdomain (e.g. api.everychurch.co)
  // — that would let this go back to a single same-origin rule everywhere,
  // rather than hardcoding the Cloud Run hostname here.
  private static readonly PROD_API_WS_ORIGIN = 'wss://everychurch-server-274948593192.us-east1.run.app';
  private static readonly PROXIED_HOSTS = ['everychurch.co', 'www.everychurch.co'];

  /** ws:// or wss:// on the page's own origin for local dev (the dev proxy forwards
   *  /ws) — see PROD_API_WS_ORIGIN above for why the deployed site is a special case. */
  private brokerUrl(): string {
    const host = window.location.host;
    if (ChatService.PROXIED_HOSTS.includes(host) || host.endsWith('.vercel.app')) {
      return `${ChatService.PROD_API_WS_ORIGIN}/ws/websocket`;
    }
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${scheme}://${host}/ws/websocket`;
  }
}
