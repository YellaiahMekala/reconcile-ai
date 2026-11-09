package com.reconcileai.infrastructure.ai.agent;

import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.EvidenceRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Builds one {@link ReconTools} per agent run; the tools themselves are not Spring beans (they hold run state). */
@Component
public class ReconToolsFactory {

    private final BreakItemRepository breaks;
    private final EvidenceRepository evidence;
    private final HybridRetriever retriever;
    private final ResolutionProposalRepository proposals;
    private final InjectionScanner scanner;
    private final MeterRegistry meters;
    private final AgentProperties props;

    public ReconToolsFactory(BreakItemRepository breaks, EvidenceRepository evidence, HybridRetriever retriever,
                             ResolutionProposalRepository proposals, InjectionScanner scanner, MeterRegistry meters,
                             AgentProperties props) {
        this.breaks = breaks;
        this.evidence = evidence;
        this.retriever = retriever;
        this.proposals = proposals;
        this.scanner = scanner;
        this.meters = meters;
        this.props = props;
    }

    public ReconTools create(AgentRunContext ctx) {
        return new ReconTools(ctx, breaks, evidence, retriever, proposals, scanner, meters, props.maxToolResultChars());
    }
}
