package com.reconcileai.application.investigation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for investigations.
 *
 * <ul>
 *   <li><b>Bounded queue + AbortPolicy</b>: when full, submission throws and the API answers 503 (back-pressure).
 *       An unbounded queue would hide overload until the JVM runs out of memory.</li>
 *   <li><b>Small pool</b>: LLM calls are I/O-bound but rate-limited by the provider; more threads mostly means more 429s.</li>
 *   <li><b>No SecurityContext propagation</b> on purpose: jobs carry tenant and maker explicitly (see InvestigationJob).</li>
 *   <li><b>Graceful shutdown</b>: waits for running jobs; anything cut off is recovered by heartbeat lease.</li>
 * </ul>
 * {@code nodeId} identifies this instance in job ownership (useful when several instances run behind a load balancer).
 */
@Configuration
@EnableScheduling
public class InvestigationExecutorConfig {

    @Bean(name = "investigationExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskExecutor investigationExecutor(InvestigationProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("investigation-");
        // Carries the caller's trace context (Micrometer context-propagation) onto the pool thread, so the job span
        // is a child of the POST /investigations request span. Only registered ThreadLocal accessors are copied:
        // the SecurityContext is NOT propagated (jobs take identity from the job row, by design).
        ex.setTaskDecorator(new org.springframework.core.task.support.ContextPropagatingTaskDecorator());
        ex.setCorePoolSize(props.corePoolSize());
        ex.setMaxPoolSize(props.maxPoolSize());
        ex.setQueueCapacity(props.queueCapacity());
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }

    @Bean
    NodeIdentity nodeIdentity() {
        return new NodeIdentity(ManagementFactory.getRuntimeMXBean().getName()); // "pid@hostname"
    }

    public record NodeIdentity(String id) {}
}
