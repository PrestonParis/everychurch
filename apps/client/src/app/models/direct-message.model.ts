/** Matches server/.../dto/DirectMessageDto.java. */
export interface DirectMessage {
  id: number;
  senderId: number;
  recipientId: number;
  senderDisplayName: string;
  body: string;
  sentAt: string;
}

/** Matches server/.../dto/ConversationSummaryDto.java. */
export interface ConversationSummary {
  otherUserId: number;
  otherUserDisplayName: string;
  lastMessageBody: string;
  lastMessageSentAt: string;
  lastMessageFromMe: boolean;
  unreadCount: number;
}

/** Mirrors DirectMessageService.MAX_BODY_LENGTH on the server. */
export const MAX_DIRECT_MESSAGE_LENGTH = 2000;

/** Same de-dup/merge reasoning as chat.model.ts's mergeMessages — a message can
 *  legitimately arrive twice (once live, again in a history reload after reconnect). */
export function mergeDirectMessages(existing: DirectMessage[], incoming: DirectMessage[]): DirectMessage[] {
  const byId = new Map<number, DirectMessage>();
  for (const message of [...existing, ...incoming]) byId.set(message.id, message);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}
