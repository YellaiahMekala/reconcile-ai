package com.reconcileai.api;

import com.reconcileai.api.dto.InvestigationJobResponse;
import com.reconcileai.api.dto.ProposalResponse;
import com.reconcileai.application.investigation.InvestigationEventsService;
import com.reconcileai.application.investigation.InvestigationService;
import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.infrastructure.ai.agent.AgentToolCallStore;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Investigation and proposal endpoints.
 * <pre>
 *  POST /api/v1/breaks/{breakId}/investigations   RECON_ANALYST   202 | 400 | 404 | 409 | 503
 *  GET  /api/v1/investigations/{jobId}            any app role    200 | 404
 *  GET  /api/v1/investigations/{jobId}/events     any app role    text/event-stream | 404 | 503
 *  GET  /api/v1/investigations/{jobId}/tool-calls any app role    200 | 404
 *  GET  /api/v1/breaks/{breakId}/proposals        any app role    200 | 404
 *  GET  /api/v1/proposals/{proposalId}            any app role    200 | 404
 * </pre>
 * 202 Accepted: the model call is asynchronous. Clients poll the job, or subscribe to its SSE stream.
 */
@RestController
public class InvestigationController {

    private static final String ANY_APP_ROLE = "hasAnyRole('RECON_ANALYST','APPROVER_L1','APPROVER_L2','SRE_ADMIN')";

    private final InvestigationService service;
    private final InvestigationEventsService events;

    public InvestigationController(InvestigationService service, InvestigationEventsService events) {
        this.service = service;
        this.events = events;
    }

    /**
     * Optional body. No tenant, break, or user fields: those come from the path and the JWT.
     * {@code mode} is SINGLE_CALL (default) or AGENT; AGENT also needs reconcile.agent.enabled=true.
     */
    public record StartInvestigationRequest(@Size(max = 1000) String analystNote, @Size(max = 16) String mode) {}

    @PostMapping("/api/v1/breaks/{breakId}/investigations")
    @PreAuthorize("hasRole('RECON_ANALYST')")
    public ResponseEntity<InvestigationJobResponse> start(@PathVariable UUID breakId,
                                                          @RequestBody(required = false) StartInvestigationRequest body,
                                                          UriComponentsBuilder uri) {
        InvestigationJob job = service.start(breakId, body == null ? null : body.analystNote(), body == null ? null : body.mode());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .location(uri.path("/api/v1/investigations/{id}").buildAndExpand(job.getId()).toUri())
            .body(InvestigationJobResponse.from(job));
    }

    @GetMapping("/api/v1/investigations/{jobId}")
    @PreAuthorize(ANY_APP_ROLE)
    public InvestigationJobResponse job(@PathVariable UUID jobId) {
        return InvestigationJobResponse.from(service.getJob(jobId));
    }

    /** Lifecycle events only (status/stage/proposalId). Needs the Authorization header: use fetch(), not EventSource. */
    @GetMapping(value = "/api/v1/investigations/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(ANY_APP_ROLE)
    public SseEmitter jobEvents(@PathVariable UUID jobId) {
        return events.subscribe(jobId);
    }

    /** What the agent looked at: tool, outcome and size per call, never the arguments. */
    @GetMapping("/api/v1/investigations/{jobId}/tool-calls")
    @PreAuthorize(ANY_APP_ROLE)
    public List<AgentToolCallStore.StoredToolCall> toolCalls(@PathVariable UUID jobId) {
        return service.toolCalls(jobId);
    }

    @GetMapping("/api/v1/breaks/{breakId}/proposals")
    @PreAuthorize(ANY_APP_ROLE)
    public List<ProposalResponse> proposalsForBreak(@PathVariable UUID breakId) {
        return service.proposalsForBreak(breakId).stream().map(ProposalResponse::from).toList();
    }

    @GetMapping("/api/v1/proposals/{proposalId}")
    @PreAuthorize(ANY_APP_ROLE)
    public ProposalResponse proposal(@PathVariable UUID proposalId) {
        return ProposalResponse.from(service.getProposal(proposalId));
    }
}
