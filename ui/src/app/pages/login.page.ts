import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { errorText } from '../core/api.service';
import { Persona } from '../core/models';

@Component({
  selector: 'app-login',
  template: `
    <section class="card narrow">
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
    </section>
  `,
})
export class LoginPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly personas = signal<Persona[]>([]);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
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
