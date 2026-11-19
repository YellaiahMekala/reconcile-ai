// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { errorText } from '../core/api.service';
import { AuthConfig, Persona } from '../core/models';
import { startLogin } from '../core/oidc';

@Component({
  selector: 'app-login',
  template: `
    <section class="card narrow">
      @if (config()?.mode === 'oidc') {
        <h1>Sign in</h1>
        <p class="muted">Sign in with the organisation's identity provider. Your tenant and roles come from it.</p>
        <button type="button" class="primary" data-oidc (click)="sso()">Sign in with identity provider</button>
        <p class="muted small">{{ config()?.issuer }}</p>
      } @else {
      <h1>Sign in (local demo)</h1>
      <p class="muted">
        Pick a persona. The backend's <code>demo-login</code> profile signs a one-hour token with the throwaway dev
        key; every request is then checked by the normal JWT, role and tenant rules.
      </p>
      @if (error()) {
        <p class="error">{{ error() }}. Is the API running with the <code>demo-login</code> profile?</p>
      }
      <ul class="personas">
        @for (p of personas(); track p.id) {
          <li>
            <button type="button" (click)="pick(p)" [attr.data-persona]="p.id">
              <strong>{{ p.label }}</strong>
              <span class="muted">{{ p.subject }} · {{ p.tenantId }} · {{ p.roles.join(', ') }}</span>
            </button>
          </li>
        }
      </ul>
      }
    </section>
  `,
})
export class LoginPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly personas = signal<Persona[]>([]);
  readonly error = signal<string | null>(null);
  readonly config = signal<AuthConfig | null>(null);

  ngOnInit(): void {
    this.auth.config().subscribe({
      next: (c) => {
        this.config.set(c);
        if (c.mode === 'demo') this.loadPersonas();
      },
      error: () => this.loadPersonas(), // older API without /auth/config: demo personas
    });
  }

  sso(): void {
    const c = this.config();
    if (c?.issuer && c.clientId) {
      startLogin(c.issuer, c.clientId).catch((e) => this.error.set(e instanceof Error ? e.message : String(e)));
    }
  }

  private loadPersonas(): void {
    this.auth.personas().subscribe({
      next: (p) => this.personas.set(p),
      error: (e) => this.error.set(errorText(e)),
    });
  }

  pick(p: Persona): void {
    this.auth.login(p.id).subscribe({
      next: () => this.router.navigate([p.roles.includes('SRE_ADMIN') ? '/audit' : p.roles.some((r) => r.startsWith('APPROVER')) ? '/approvals' : '/breaks']),
      error: (e) => this.error.set(errorText(e)),
    });
  }
}
