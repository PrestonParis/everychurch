/** Matches server/.../dto/MessageDto.java. */
export interface ChatMessage {
  id: number;
  churchId: number;
  senderDisplayName: string;
  body: string;
  sentAt: string;
}

export type ChatConnectionState = 'connecting' | 'connected' | 'disconnected' | 'error';

/** Mirrors ChatService.MAX_BODY_LENGTH on the server, which drops anything longer. */
export const MAX_CHAT_MESSAGE_LENGTH = 2000;

/**
 * Merges freshly-loaded messages into what's already shown: de-duplicated by id and kept
 * in id order (ids grow with time). A message can legitimately arrive twice — once live
 * over the socket and again in a history reload after a reconnect — so appending blindly
 * would show it twice.
 */
export function mergeMessages(existing: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const byId = new Map<number, ChatMessage>();
  for (const message of [...existing, ...incoming]) byId.set(message.id, message);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}
