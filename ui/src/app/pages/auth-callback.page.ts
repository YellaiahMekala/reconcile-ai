// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { completeLogin } from '../core/oidc';

/** Landing page for the identity provider's redirect: exchanges the code (PKCE) and starts the session. */
@Component({
  selector: 'app-auth-callback',
  imports: [RouterLink],
  template: `
    <section class="card narrow">
      @if (error()) {
        <h1>Sign-in failed</h1>
        <p class="error">{{ error() }}</p>
        <a routerLink="/login">Back to sign in</a>
      } @else {
        <p class="muted">Signing you in…</p>
      }
    </section>
  `,
})
export class AuthCallbackPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const session = await completeLogin(window.location.search);
      this.auth.adopt(session);
      const roles = session.persona.roles;
      this.router.navigateByUrl(
        roles.includes('SRE_ADMIN') ? '/audit' : roles.some((r) => r.startsWith('APPROVER')) ? '/approvals' : '/breaks',
        { replaceUrl: true }, // drop ?code=&state= from history
      );
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : String(e));
    }
  }
}
