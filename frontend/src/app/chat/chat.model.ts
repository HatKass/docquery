/** Mirrors api-service `ChatDtos`. */
export interface ConversationSummary {
  id: string;
  workspaceId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationDetail extends ConversationSummary {
  messages: ChatMessage[];
}

export type MessageRole = 'USER' | 'ASSISTANT';
export type MessageStatus = 'COMPLETE' | 'FAILED';

export interface Citation {
  /** The `[n]` marker in the answer text. */
  sourceNumber: number;
  /** `null` once the document has been deleted. */
  documentId: string | null;
  documentName: string;
  contentType: string;
  /** 1-based page for PDFs; `null` for unpaginated formats. */
  pageNumber: number | null;
  snippet: string;
}

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  status: MessageStatus;
  createdAt: string;
  citations: Citation[];
}

/** One event of an answer stream (`POST .../conversations/{id}/messages`). */
export type AnswerStreamEvent =
  | { type: 'start'; question: ChatMessage; answerId: string }
  | { type: 'delta'; text: string }
  | { type: 'done'; answer: ChatMessage }
  | { type: 'error'; answer: ChatMessage | null; detail: string };

/** The exact sentence the assistant uses when the documents do not answer a question. */
export const NOT_FOUND_ANSWER = "I couldn't find this in your documents.";
