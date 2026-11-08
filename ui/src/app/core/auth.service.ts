import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { AuthConfig, Persona, Session } from './models';
import { idpLogoutUrl } from './oidc';

const KEY = 'reconcile-ai.session';

/**
 * Holds the demo session (token + persona) in sessionStorage. The UI never decides access: it only hides
 * actions the persona cannot perform. The backend enforces every 401/403/404 rule.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly session = signal<Session | null>(AuthService.restore());
  readonly persona = computed(() => this.session()?.persona ?? null);
  readonly loggedIn = computed(() => this.session() !== null);

  /** demo (persona tokens) or oidc (real identity provider), decided by the API's configuration. */
  config(): Observable<AuthConfig> {
    return this.http.get<AuthConfig>('/api/v1/auth/config');
  }

  /** Stores a session obtained from the identity provider (see oidc.ts). */
  adopt(s: Session): void {
    sessionStorage.setItem(KEY, JSON.stringify(s));
    this.session.set(s);
  }

  personas(): Observable<Persona[]> {
    return this.http.get<Persona[]>('/dev/personas');
  }

  login(personaId: string): Observable<Session> {
    return this.http.post<Session>('/dev/token', { persona: personaId }).pipe(
      tap((s) => {
        sessionStorage.setItem(KEY, JSON.stringify(s));
        this.session.set(s);
      }),
    );
  }

  logout(): void {
    const ended = this.session();
    sessionStorage.removeItem(KEY);
    this.session.set(null);
    if (ended?.idToken) {
      // Also end the IdP session, otherwise the next "Sign in" would silently reuse it.
      this.config().subscribe({
        next: async (c) => {
          const url = c.issuer ? await idpLogoutUrl(c.issuer, ended.idToken) : null;
          if (url) window.location.assign(url);
          else this.router.navigate(['/login']);
        },
        error: () => this.router.navigate(['/login']),
      });
      return;
    }
    this.router.navigate(['/login']);
  }

  token(): string | null {
    return this.session()?.token ?? null;
  }

  hasRole(...roles: string[]): boolean {
    const mine = this.persona()?.roles ?? [];
    return roles.some((r) => mine.includes(r));
  }

  private static restore(): Session | null {
    try {
      const raw = sessionStorage.getItem(KEY);
      if (!raw) return null;
      const s = JSON.parse(raw) as Session;
      return new Date(s.expiresAt).getTime() > Date.now() ? s : null;
    } catch {
      return null;
    }
  }
}
