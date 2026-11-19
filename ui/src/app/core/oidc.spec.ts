// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { base64Url, claimsOf, codeChallenge, completeLogin, randomString, sessionFromTokens } from './oidc';

function fakeJwt(claims: object): string {
  const enc = (o: object) => base64Url(new TextEncoder().encode(JSON.stringify(o)));
  return enc({ alg: 'RS256' }) + '.' + enc(claims) + '.sig';
}

describe('oidc (PKCE)', () => {
  it('computes the S256 challenge from RFC 7636 Appendix B', async () => {
    expect(await codeChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('generates url-safe random verifiers that differ every time', () => {
    const a = randomString(48);
    expect(a).toMatch(/^[A-Za-z0-9_-]{64}$/);
    expect(randomString(48)).not.toBe(a);
  });

  it('builds the session (tenant, roles, display name) from the access token claims', () => {
    const token = fakeJwt({ sub: 'u-1', preferred_username: 'analyst', name: 'Demo Analyst', tenant_id: 'TENANT_DEMO', roles: ['RECON_ANALYST'] });
    const s = sessionFromTokens(token, 'id-tok', 1800);
    expect(claimsOf(token)['tenant_id']).toBe('TENANT_DEMO');
    expect(s.persona.tenantId).toBe('TENANT_DEMO');
    expect(s.persona.roles).toEqual(['RECON_ANALYST']);
    expect(s.persona.label).toBe('Demo Analyst');
    expect(s.idToken).toBe('id-tok');
    expect(new Date(s.expiresAt).getTime()).toBeGreaterThan(Date.now());
  });

  it('rejects a callback whose state does not match (CSRF / mix-up protection)', async () => {
    sessionStorage.setItem('reconcile-ai.oidc.pending',
      JSON.stringify({ issuer: 'http://idp', clientId: 'c', verifier: 'v', state: 'expected' }));
    await expectAsync(completeLogin('?code=abc&state=forged')).toBeRejectedWithError(/state mismatch/);
    // the pending login is single-use
    expect(sessionStorage.getItem('reconcile-ai.oidc.pending')).toBeNull();
  });
});
