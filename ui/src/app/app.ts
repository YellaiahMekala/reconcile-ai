// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="top">
      <span class="brand">reconcile-ai</span>
      @if (auth.persona(); as p) {
        <nav>
          <a routerLink="/breaks" routerLinkActive="active">Breaks</a>
          @if (auth.hasRole('APPROVER_L1', 'APPROVER_L2')) {
            <a routerLink="/approvals" routerLinkActive="active">Approvals</a>
          }
          @if (auth.hasRole('SRE_ADMIN')) {
            <a routerLink="/audit" routerLinkActive="active">Audit</a>
          }
        </nav>
        <span class="who">{{ p.label }} · {{ p.tenantId }}</span>
        <button type="button" class="secondary" (click)="auth.logout()">Sign out</button>
      }
    </header>
    <main><router-outlet /></main>
    <footer class="muted">Independent demonstration project. Synthetic data only.</footer>
  `,
})
export class App {
  readonly auth = inject(AuthService);
}
