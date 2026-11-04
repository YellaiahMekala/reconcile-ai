package com.reconcileai.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TEST-ONLY deterministic embedding model: 1536-dim "hashing trick" bag-of-words vectors.
 *
 * <p>Each lowercase token is hashed into one of 1536 buckets (+1 or -1 by a second hash bit), then the vector
 * is L2-normalised. Texts sharing words therefore get genuinely higher cosine similarity, which makes retrieval
 * tests meaningful without network calls, cost, or flakiness.
 *
 * <p>Locked rule: lives under src/test, active only in the "test" profile. It can never be picked up by a
 * runnable profile, where embeddings are always OpenAI text-embedding-3-small.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1536;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        List<String> inputs = request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            out.add(new Embedding(vectorize(inputs.get(i)), i));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return vectorize(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    static float[] vectorize(String text) {
        float[] v = new float[DIMENSIONS];
        if (text != null) {
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() < 2) {
                    continue;
                }
                int h = murmurish(token);
                v[Math.floorMod(h, DIMENSIONS)] += ((h >>> 16) & 1) == 0 ? 1f : -1f;
            }
        }
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        if (norm == 0) {
            v[0] = 1f; // empty text: fixed unit vector (pgvector cosine distance is undefined for zero vectors)
            return v;
        }
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
        return v;
    }

    private static int murmurish(String s) {
        int h = 0x9747b28c;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        return h;
    }
}
