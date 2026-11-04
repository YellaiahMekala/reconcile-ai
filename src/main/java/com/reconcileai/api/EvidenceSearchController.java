package com.reconcileai.api;

import com.reconcileai.application.EvidenceSearchService;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * GET /api/v1/breaks/{breakId}/evidence/search?q=... — hybrid retrieval preview, tenant-scoped.
 * Snippets are truncated to 300 chars so the endpoint cannot be used to bulk-export evidence text.
 */
@RestController

@RequestMapping("/api/v1/breaks/{breakId}/evidence/search")
public class EvidenceSearchController {

    private final EvidenceSearchService searchService;

    public EvidenceSearchController(EvidenceSearchService searchService) {
        this.searchService = searchService;
    }

    public record SearchHit(String chunkId, UUID evidenceId, String fileName, int chunkIndex,
                            Double vectorScore, Integer keywordRank, double fusedScore, String snippet) {
        static SearchHit from(RetrievedChunk c) {
            String t = c.text();
            return new SearchHit(c.chunkId(), c.evidenceId(), c.fileName(), c.chunkIndex(),
                c.vectorScore(), c.keywordRank(), c.fusedScore(), t.length() > 300 ? t.substring(0, 300) + "…" : t);
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('RECON_ANALYST','APPROVER_L1','APPROVER_L2')")
    public ResponseEntity<List<SearchHit>> search(@PathVariable UUID breakId,
                                                  @RequestParam("q") @NotBlank @Size(max = 500) String q) {
        return ResponseEntity.ok(searchService.search(breakId, q).stream().map(SearchHit::from).toList());
    }
}
