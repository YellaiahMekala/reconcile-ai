package com.reconcileai.infrastructure.ai.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Least-privilege tool sets for the supervised-mode specialists. Each is a thin view over the run's {@link ReconTools},
 * so every call still goes through the same guard (budget, deadline, role, injection scan, PII mask, length cap) and
 * lands in the same trace. The reviewer gets no tools at all.
 */
final class SpecialistTools {

    private SpecialistTools() {}

    /** Evidence analyst: what the break is, which files exist, and search. No access to earlier resolutions. */
    static final class Analyst {
        private final ReconTools tools;

        Analyst(ReconTools tools) {
            this.tools = tools;
        }

        @Tool("Get the reference, ledger amount, external amount, variance, currency and status of the break being investigated")
        public String getBreakSummary() {
            return tools.getBreakSummary();
        }

        @Tool("List the evidence files uploaded for this break: file name, processing status and number of text chunks")
        public String listEvidence() {
            return tools.listEvidence();
        }

        @Tool("Search this break's evidence for passages relevant to a question. Returns evidence blocks; cite them by chunk_id")
        public String searchEvidence(@P("what to look for, for example 'wire fee deducted' or 'settlement date'") String query) {
            return tools.searchEvidence(query);
        }
    }

    /** Risk checker: only how earlier, similar breaks were handled. It cannot search evidence. */
    static final class Risk {
        private final ReconTools tools;

        Risk(ReconTools tools) {
            this.tools = tools;
        }

        @Tool("Find up to 5 earlier breaks of this tenant that were approved with a given root cause, with their variance band and action taken")
        public String findSimilarResolvedBreaks(
                @P("one of TIMING_DIFFERENCE, FX_RATE_DIFFERENCE, FEE_OR_CHARGE, DUPLICATE_ENTRY, MISSING_ENTRY, DATA_ENTRY_ERROR, PARTIAL_SETTLEMENT") String rootCause) {
            return tools.findSimilarResolvedBreaks(rootCause);
        }
    }
}
