// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { Observable } from 'rxjs';

export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Server-sent events over fetch(). Native EventSource cannot send an Authorization header, so the stream is read
 * with fetch + ReadableStream and parsed here. Unsubscribing aborts the request.
 */
export function sseStream(url: string, token: string): Observable<SseMessage> {
  return new Observable<SseMessage>((subscriber) => {
    const controller = new AbortController();
    (async () => {
      try {
        const res = await fetch(url, {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
          signal: controller.signal,
        });
        if (!res.ok || !res.body) {
          subscriber.error(new Error(`SSE HTTP ${res.status}`));
          return;
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let idx: number;
          while ((idx = buffer.search(/\r?\n\r?\n/)) >= 0) {
            const frame = buffer.slice(0, idx);
            buffer = buffer.slice(idx).replace(/^\r?\n\r?\n/, '');
            const msg = parseFrame(frame);
            if (msg) subscriber.next(msg);
          }
        }
        subscriber.complete();
      } catch (e) {
        if (!controller.signal.aborted) subscriber.error(e);
      }
    })();
    return () => controller.abort();
  });
}

/** Parses one SSE frame. Comment-only frames (keep-alives) return null. */
export function parseFrame(frame: string): SseMessage | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith(':')) continue;
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  return data.length ? { event, data: data.join('\n') } : null;
}
