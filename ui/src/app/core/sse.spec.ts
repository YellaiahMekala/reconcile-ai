// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { parseFrame } from './sse';

describe('parseFrame', () => {
  it('parses a named event with JSON data', () => {
    expect(parseFrame('event:progress\ndata:{"stage":"ROUTING"}')).toEqual({
      event: 'progress',
      data: '{"stage":"ROUTING"}',
    });
  });

  it('joins multi-line data and strips one leading space', () => {
    expect(parseFrame('data: a\ndata: b')).toEqual({ event: 'message', data: 'a\nb' });
  });

  it('ignores keep-alive comment frames', () => {
    expect(parseFrame(':keep-alive')).toBeNull();
  });
});
