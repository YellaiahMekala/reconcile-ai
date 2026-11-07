import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { Persona, Session } from './models';

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
    sessionStorage.removeItem(KEY);
    this.session.set(null);
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
