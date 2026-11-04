package com.reconcileai.api;

import com.reconcileai.api.dto.EvidenceResponse;
import com.reconcileai.application.EvidenceService;
import com.reconcileai.application.EvidenceUploadResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * REST API for evidence attached to a break.
 *
 * <p>POST returns 201 for a new file and 200 when the identical file was already uploaded
 * (hash dedup), so clients can retry safely. A breakId from another tenant is 404 on both verbs.
 * Max file size is set in application.yml (spring.servlet.multipart).
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
}
