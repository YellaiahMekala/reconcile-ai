package com.reconcileai.infrastructure.ai.agent;

import com.reconcileai.infrastructure.ai.agent.ReviewerVerdict.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The verdict is made in code from per-claim quotes; these are the cases where a model's answer must not be trusted. */
class ReviewerVerdictTest {

    static final String FEE = "The intermediary bank deducted a wire fee of 40.00 USD before crediting the beneficiary.";
    static final String TIMING = "Payment initiated 2026-03-31,\n   settled next business day.";
    final Map<String, String> chunks = Map.of("c1", FEE, "c2", TIMING);

    private ReviewerVerdict review(String json, String... cited) {
        return ReviewerVerdict.fromModel(json, List.of(cited), chunks::get);
    }

    @Test
    void verbatimQuotesForEveryCitationAgree() {
        ReviewerVerdict v = review("""
            {"claims":[{"chunkId":"c1","supported":true,"quote":"deducted a wire fee of 40.00 USD"},
                       {"chunkId":"c2","supported":true,"quote":"settled next business day"}]}""", "c1", "c2");
        assertThat(v.verdict()).isEqualTo(Verdict.AGREE);
        assertThat(v.challenged()).isFalse();
        assertThat(v.claims()).allMatch(ReviewerVerdict.ClaimCheck::supported);
    }

    @Test
    void whitespaceCaseAndSurroundingQuoteMarksDoNotMatter() {
        ReviewerVerdict v = review("""
            {"claims":[{"chunkId":"c2","supported":true,"quote":"\\"PAYMENT INITIATED 2026-03-31, settled next business day.\\""}]}""",
            "c2");
        assertThat(v.verdict()).isEqualTo(Verdict.AGREE);
    }

    @Test
    void aQuoteThatIsNotInTheChunkIsAChallengeEvenIfTheModelSaysSupported() {
        ReviewerVerdict v = review("""
            {"verdict":"AGREE","claims":[{"chunkId":"c1","supported":true,"quote":"the fee was waived by agreement"}]}""", "c1");
        assertThat(v.verdict()).isEqualTo(Verdict.CHALLENGE);
        assertThat(v.claims().get(0).reason()).isEqualTo("QUOTE_NOT_IN_CHUNK");
    }

    @Test
    void aTinyQuoteProvesNothing() {
        ReviewerVerdict v = review("""
            {"claims":[{"chunkId":"c1","supported":true,"quote":"the"}]}""", "c1");
        assertThat(v.claims().get(0).reason()).isEqualTo("QUOTE_TOO_SHORT");
        assertThat(v.challenged()).isTrue();
    }

    @Test
    void aCitationTheReviewerSkippedCountsAsUnsupported() {
        ReviewerVerdict v = review("""
            {"claims":[{"chunkId":"c1","supported":true,"quote":"deducted a wire fee of 40.00 USD"}]}""", "c1", "c2");
        assertThat(v.verdict()).isEqualTo(Verdict.CHALLENGE);
        assertThat(v.claims()).extracting(ReviewerVerdict.ClaimCheck::reason).containsExactly(null, "NOT_CHECKED");
    }

    @Test
    void anIdThatWasNeverRetrievedInThisRunCannotBeSupported() {
        ReviewerVerdict v = review("""
            {"claims":[{"chunkId":"ghost","supported":true,"quote":"deducted a wire fee of 40.00 USD"}]}""", "ghost");
        assertThat(v.claims().get(0).reason()).isEqualTo("NOT_RETRIEVED");
        assertThat(v.challenged()).isTrue();
    }

    @Test
    void theModelSayingUnsupportedIsRespected() {
        ReviewerVerdict v = review("""
            {"claims":[{"chunkId":"c1","supported":false,"quote":""}]}""", "c1");
        assertThat(v.claims().get(0).reason()).isEqualTo("MODEL_SAID_UNSUPPORTED");
        assertThat(v.challenged()).isTrue();
    }

    @Test
    void unreadableOutputIsAChallengeAndJsonInsideProseIsStillRead() {
        assertThat(review("I think it looks fine.", "c1").note()).isEqualTo("REVIEW_OUTPUT_UNREADABLE");
        assertThat(review("I think it looks fine.", "c1").challenged()).isTrue();

        ReviewerVerdict fenced = review("""
            Here is my review:
            ```json
            {"claims":[{"chunkId":"c1","supported":true,"quote":"wire fee of 40.00 USD"}]}
            ```""", "c1");
        assertThat(fenced.verdict()).isEqualTo(Verdict.AGREE);
    }

    @Test
    void noCitationsMeansNoAgreement() {
        assertThat(ReviewerVerdict.enforce(List.of(), List.of(), chunks::get).verdict()).isEqualTo(Verdict.CHALLENGE);
        assertThat(ReviewerVerdict.notRun("NOTHING_TO_REVIEW").challenged()).isFalse();
    }
}
