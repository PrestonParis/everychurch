import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Client, StompConfig } from '@stomp/stompjs';
import { ChatMessage } from '../models/chat.model';
import { AuthService } from './auth.service';
import { ChatService, STOMP_CLIENT_FACTORY } from './chat.service';

/**
 * A stand-in for the real STOMP client: records what ChatService asks of it and lets a test
 * call back into the handlers ChatService registered, as the real client would on a socket
 * event. That's the whole surface ChatService uses, so nothing here needs a live server —
 * the end-to-end socket behavior is covered by the server's ChatFlowTest.
 */
class FakeStompClient {
  connected = false;
  connectHeaders: Record<string, string> = {};
  activate = jest.fn();
  deactivate = jest.fn().mockResolvedValue(undefined);
  publish = jest.fn();
  subscribe = jest.fn();
  constructor(public config: StompConfig) {}
}

describe('ChatService', () => {
  let service: ChatService;
  let http: HttpTestingController;
  let fake: FakeStompClient;
  let token: string | null;

  beforeEach(() => {
    token = 'jwt-abc';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { getToken: () => token } },
        {
          provide: STOMP_CLIENT_FACTORY,
          useValue: (config: StompConfig) => {
            fake = new FakeStompClient(config);
            return fake as unknown as Client;
          },
        },
      ],
    });
    service = TestBed.inject(ChatService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const chatMessage = (id: number): ChatMessage => ({
    id,
    churchId: 7,
    senderDisplayName: 'alice',
    body: 'hi',
    sentAt: '2026-01-01T00:00:00Z',
  });

  describe('history', () => {
    it('requests the latest page with no query string by default', () => {
      service.history(7).subscribe();
      const req = http.expectOne('/api/churches/7/messages');
      expect(req.request.params.keys()).toEqual([]);
      req.flush([]);
    });

    it('passes the cursor and limit through', () => {
      service.history(7, 12, 50).subscribe();
      const req = http.expectOne((r) => r.url === '/api/churches/7/messages');
      expect(req.request.params.get('before')).toBe('12');
      expect(req.request.params.get('limit')).toBe('50');
      req.flush([]);
    });
  });

  describe('connect', () => {
    it('opens the raw websocket endpoint on the page origin and activates the client', () => {
      service.connect(7);
      expect(fake.config.brokerURL).toMatch(/^wss?:\/\/[^/]+\/ws\/websocket$/);
      expect(fake.activate).toHaveBeenCalledTimes(1);
    });

    it('puts the JWT on the STOMP CONNECT headers, and re-reads it on every reconnect', () => {
      const connection = service.connect(7);
      expect(connection.state()).toBe('connecting');

      fake.config.beforeConnect!(fake as unknown as Client);
      expect(fake.connectHeaders).toEqual({ Authorization: 'Bearer jwt-abc' });

      token = 'jwt-refreshed';
      fake.config.beforeConnect!(fake as unknown as Client);
      expect(fake.connectHeaders).toEqual({ Authorization: 'Bearer jwt-refreshed' });
    });

    it('subscribes to this church\'s chat topic once connected', () => {
      const connection = service.connect(7);
      fake.config.onConnect!({} as never);

      expect(connection.state()).toBe('connected');
      expect(fake.subscribe).toHaveBeenCalledWith('/topic/churches/7/chat', expect.any(Function));
    });

    it('emits parsed messages from incoming frames', () => {
      const connection = service.connect(7);
      const received: ChatMessage[] = [];
      connection.messages$.subscribe((m) => received.push(m));
      fake.config.onConnect!({} as never);

      const onFrame = fake.subscribe.mock.calls[0][1];
      onFrame({ body: JSON.stringify(chatMessage(1)) });

      expect(received).toEqual([chatMessage(1)]);
    });

    it('ignores a frame that is not valid JSON instead of crashing', () => {
      const connection = service.connect(7);
      const received: ChatMessage[] = [];
      connection.messages$.subscribe((m) => received.push(m));
      fake.config.onConnect!({} as never);

      const onFrame = fake.subscribe.mock.calls[0][1];
      expect(() => onFrame({ body: 'not json' })).not.toThrow();
      expect(received).toEqual([]);
    });
  });

  describe('send', () => {
    it('publishes trimmed JSON to this church\'s send destination', () => {
      const connection = service.connect(7);
      fake.connected = true;

      connection.send('  hello there  ');

      expect(fake.publish).toHaveBeenCalledWith({
        destination: '/app/churches/7/chat.send',
        body: JSON.stringify({ body: 'hello there' }),
        headers: { 'content-type': 'application/json' },
      });
    });

    it('does not publish blank messages', () => {
      const connection = service.connect(7);
      fake.connected = true;

      connection.send('   ');

      expect(fake.publish).not.toHaveBeenCalled();
    });

    it('does not publish while disconnected rather than queueing it silently', () => {
      const connection = service.connect(7);
      fake.connected = false;

      connection.send('hello');

      expect(fake.publish).not.toHaveBeenCalled();
    });
  });

  describe('connection lifecycle', () => {
    it('reports disconnected when the socket drops, then connected again after a reconnect', () => {
      const connection = service.connect(7);
      fake.config.onConnect!({} as never);
      expect(connection.state()).toBe('connected');

      fake.config.onWebSocketClose!({} as never);
      expect(connection.state()).toBe('disconnected');

      fake.config.beforeConnect!(fake as unknown as Client);
      expect(connection.state()).toBe('connecting');
      fake.config.onConnect!({} as never);
      expect(connection.state()).toBe('connected');
    });

    it('stops retrying and reports an error when the server rejects us with an ERROR frame', () => {
      const connection = service.connect(7);
      fake.config.onStompError!({} as never);

      expect(connection.state()).toBe('error');
      expect(fake.deactivate).toHaveBeenCalled();

      // the socket close that follows the ERROR frame must not mask it as a plain disconnect
      fake.config.onWebSocketClose!({} as never);
      expect(connection.state()).toBe('error');
    });

    it('closing deactivates the client and completes the message stream', () => {
      const connection = service.connect(7);
      let completed = false;
      connection.messages$.subscribe({ complete: () => (completed = true) });

      connection.close();

      expect(fake.deactivate).toHaveBeenCalled();
      expect(completed).toBe(true);
    });
  });
});
