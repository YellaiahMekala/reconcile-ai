// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.ingestion;

import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evidence ingestion pipeline: bytes -> Tika text -> token chunks -> embeddings -> vector_store.
 *
 * <pre>
 *  byte[] ──TikaDocumentReader──► text ──TokenTextSplitter──► chunks
 *        ──InjectionScanner──► injectionSuspect flag per chunk
 *        ──VectorStore.add()──► EmbeddingModel (OpenAI text-embedding-3-small) ──► vector_store row
 * </pre>
 *
 * <h2>Metadata contract</h2>
 * There is no EvidenceChunk JPA entity. Every chunk is a Spring AI {@link Document} whose metadata carries
 * the keys below. Retrieval filters on these keys with {@code filterExpression}, server-side, in the same
 * SQL as the similarity search. {@link #TENANT_ID} is therefore security-critical, not cosmetic.
 *
 * <h2>Idempotency</h2>
 * Chunk ids are name-based UUIDs of (evidenceId, chunkIndex), and existing chunks for the evidence are
 * deleted first, so a retry after a partial failure never leaves duplicate vectors.
 */
@Component
public class EvidenceIngestor {

    public static final String TENANT_ID = "tenantId";
    public static final String BREAK_ITEM_ID = "breakItemId";
    public static final String EVIDENCE_ID = "evidenceId";
    public static final String CHUNK_INDEX = "chunkIndex";
    public static final String FILE_NAME = "fileName";
    public static final String SOURCE_TYPE = "sourceType";
    public static final String INJECTION_SUSPECT = "injectionSuspect";

    private static final Logger log = LoggerFactory.getLogger(EvidenceIngestor.class);

    private final VectorStore vectorStore;
    private final InjectionScanner injectionScanner;
    private final TokenTextSplitter splitter;
    private final IngestionProperties props;

    public EvidenceIngestor(VectorStore vectorStore, InjectionScanner injectionScanner, IngestionProperties props) {
        this.vectorStore = vectorStore;
        this.injectionScanner = injectionScanner;
        this.props = props;
        this.splitter = TokenTextSplitter.builder()
            .withChunkSize(props.chunkSizeTokens())
            .withMinChunkSizeChars(props.minChunkSizeChars())
            .withMinChunkLengthToEmbed(props.minChunkLengthToEmbed())
            .withMaxNumChunks(props.maxChunksPerEvidence())
            .withKeepSeparator(true)
            .build();
    }

    /** Outcome passed back to EvidenceService, which owns the DB transaction. */
    public record IngestionOutcome(boolean success, int chunkCount, int suspectChunkCount, String failureReason) {
        static IngestionOutcome failed(String reason) {
            return new IngestionOutcome(false, 0, 0, reason);
        }
    }

    /**
     * Runs the full pipeline. Never throws for bad content: parse/embedding failures become a FAILED outcome
     * with a short reason, so one broken PDF cannot 500 the upload request.
     */
    public IngestionOutcome ingest(String tenantId, UUID breakItemId, UUID evidenceId,
                                   String fileName, String contentType, byte[] content) {
        try {
            List<Document> extracted = extract(fileName, contentType, content);
            String fullText = extracted.stream().map(Document::getText).filter(t -> t != null).reduce("", String::concat);
            if (fullText.isBlank()) {
                return IngestionOutcome.failed("NO_EXTRACTABLE_TEXT");
            }

            List<Document> rawChunks = splitter.apply(extracted);
            if (rawChunks.isEmpty()) {
                return IngestionOutcome.failed("NO_CHUNKS_PRODUCED");
            }

            List<Document> chunks = new ArrayList<>(rawChunks.size());
            int suspect = 0;
            for (int i = 0; i < rawChunks.size(); i++) {
                String text = rawChunks.get(i).getText();
                boolean isSuspect = injectionScanner.isSuspect(text);
                if (isSuspect) {
                    suspect++;
                    log.warn("Injection-suspect chunk quarantined: evidence={} chunk={} rule={}",
                        evidenceId, i, injectionScanner.scan(text).orElse("?"));
                }
                Map<String, Object> md = new HashMap<>();
                md.put(TENANT_ID, tenantId);
                md.put(BREAK_ITEM_ID, breakItemId.toString());
                md.put(EVIDENCE_ID, evidenceId.toString());
                md.put(CHUNK_INDEX, i);
                md.put(FILE_NAME, fileName);
                md.put(SOURCE_TYPE, contentType);
                md.put(INJECTION_SUSPECT, isSuspect);
                chunks.add(Document.builder().id(chunkId(evidenceId, i)).text(text).metadata(md).build());
            }

            // Idempotent retry: remove any chunks left by a previous failed attempt for this evidence.
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            vectorStore.delete(b.and(b.eq(TENANT_ID, tenantId), b.eq(EVIDENCE_ID, evidenceId.toString())).build());

            vectorStore.add(chunks); // embeds (batched) + inserts
            return new IngestionOutcome(true, chunks.size(), suspect, null);
        } catch (Exception e) {
            // Log the detail server-side; store only a bounded, non-sensitive reason on the row.
            log.error("Ingestion failed for evidence {}", evidenceId, e);
            return IngestionOutcome.failed("INGESTION_ERROR: " + e.getClass().getSimpleName());
        }
    }

    /** Deterministic chunk id: same evidence + index always maps to the same vector_store row id. */
    public static String chunkId(UUID evidenceId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((evidenceId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private List<Document> extract(String fileName, String contentType, byte[] content) {
        if (contentType != null && contentType.startsWith("text/plain")) {
            // Plain text needs no Tika parsing; avoids charset-detection surprises on short files.
            return List.of(new Document(new String(content, StandardCharsets.UTF_8)));
        }
        // Named resource: TikaDocumentReader records the filename in metadata and uses it as a type hint.
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        return new TikaDocumentReader(resource).get();
    }
}
