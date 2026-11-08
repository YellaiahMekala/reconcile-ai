import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.session.set({
      token: 'tok',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      persona: { id: 'analyst', subject: 'a', tenantId: 'T', roles: ['RECON_ANALYST'], label: 'A' },
    });
  });

  it('adds the bearer token to /api calls only', () => {
    http.get('/api/v1/breaks').subscribe();
    http.get('/dev/personas').subscribe();
    expect(ctrl.expectOne('/api/v1/breaks').request.headers.get('Authorization')).toBe('Bearer tok');
    expect(ctrl.expectOne('/dev/personas').request.headers.has('Authorization')).toBeFalse();
  });

  it('ends the session on 401', () => {
    http.get('/api/v1/breaks').subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/breaks').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(auth.loggedIn()).toBeFalse();
  });

  it('hasRole reflects the persona', () => {
    expect(auth.hasRole('RECON_ANALYST')).toBeTrue();
    expect(auth.hasRole('SRE_ADMIN')).toBeFalse();
  });
});
