// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point. Component scanning starts at com.reconcileai, covering
 * api / application / domain / infrastructure. AI beans are enabled by profile
 *  (mock-llm, ollama) without changing this class.
 * Method security is enabled in SecurityConfig (kept next to the rest of the security setup).
 */
@SpringBootApplication
@ConfigurationPropertiesScan // binds reconcile.* properties to the *Properties records
public class ReconcileAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconcileAiApplication.class, args);
    }
}
