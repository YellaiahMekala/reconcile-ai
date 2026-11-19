// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import com.reconcileai.api.dto.EvidenceResponse;
import com.reconcileai.application.EvidenceService;
import com.reconcileai.application.EvidenceUploadResult;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * REST API for evidence attached to a break.
 *
 * <p>POST returns 201 for a new file and 200 when the identical file was already uploaded
 * (hash dedup), so clients can retry safely. A breakId from another tenant is 404 on both verbs.
 * Max file size is set in application.yml (spring.servlet.multipart). GET ./{evidenceId}/content returns the original file.
 */
@RestController
@RequestMapping("/api/v1/breaks/{breakId}/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('RECON_ANALYST')")
    public ResponseEntity<EvidenceResponse> upload(@PathVariable UUID breakId,
                                                   @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EvidenceUploadResult result = evidenceService.upload(
            breakId,
            file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename(),
            file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
            bytes);

        EvidenceResponse body = EvidenceResponse.from(result.evidence(), result.deduplicated());
        // 200 when the upload was a dedup hit (nothing new created), 201 otherwise.
        return result.deduplicated()
            ? ResponseEntity.ok(body)
            : ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('RECON_ANALYST','APPROVER_L1','APPROVER_L2','SRE_ADMIN')")
    public ResponseEntity<List<EvidenceResponse>> list(@PathVariable UUID breakId) {
        List<EvidenceResponse> body = evidenceService.listForBreak(breakId).stream()
            .map(e -> EvidenceResponse.from(e, false))
            .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Original file, as uploaded. Always sent as an attachment with nosniff: an analyst-supplied HTML or SVG file
     * must never be rendered by the browser under the API's origin.
     */
    @GetMapping("/{evidenceId}/content")
    @PreAuthorize("hasAnyRole('RECON_ANALYST','APPROVER_L1','APPROVER_L2','SRE_ADMIN')")
    public ResponseEntity<byte[]> download(@PathVariable UUID breakId, @PathVariable UUID evidenceId) {
        EvidenceService.StoredEvidence file = evidenceService.content(breakId, evidenceId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(file.bytes());
    }
}
