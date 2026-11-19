// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.audit.anchor;

import java.time.Instant;

/** Write-once storage for signed anchors. The production implementation is S3 with Object Lock. */
public interface AnchorStore {

    /** @return the object version id (null if the store has no versioning) */
    String put(String key, byte[] body, Instant retainUntil);

    byte[] get(String key, String versionId);
}
