// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { Session } from './models';

/**
 * OpenID Connect Authorization Code flow with PKCE (RFC 7636) for a public browser client.
 *
 * Why hand-written (about 100 lines) rather than a library: the flow is small, it has no client secret, and keeping it
 * visible makes the security properties reviewable: S256 challenge, a random `state` checked on return, the verifier
 * kept only in sessionStorage for the round trip, and endpoints taken from the IdP's discovery document (works with
 * Keycloak and Entra ID alike). No refresh tokens are stored: when the access token expires the user signs in again.
 */
const PENDING = 'reconcile-ai.oidc.pending';
export const CALLBACK_PATH = '/auth/callback';

interface Discovery {
  authorization_endpoint: string;
  token_endpoint: string;
  end_session_endpoint?: string;
}

interface Pending {
  issuer: string;
  clientId: string;
  verifier: string;
  state: string;
}

export function base64Url(bytes: Uint8Array): string {
  let s = '';
  bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function randomString(byteLength = 32): string {
  const b = new Uint8Array(byteLength);
  crypto.getRandomValues(b);
  return base64Url(b);
}

/** S256 code challenge = BASE64URL(SHA-256(ASCII(verifier))). */
export async function codeChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

/** Reads the claims of a JWT for DISPLAY only (the API verifies the signature; the browser never trusts claims). */
export function claimsOf(jwt: string): Record<string, unknown> {
  const part = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  const json = decodeURIComponent(
    atob(part.padEnd(part.length + ((4 - (part.length % 4)) % 4), '='))
      .split('')
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join(''),
  );
  return JSON.parse(json);
}

export function sessionFromTokens(accessToken: string, idToken: string | undefined, expiresInSeconds: number): Session {
  const c = claimsOf(accessToken);
  const roles = Array.isArray(c['roles']) ? (c['roles'] as string[]) : [];
  return {
    token: accessToken,
    idToken,
    expiresAt: new Date(Date.now() + expiresInSeconds * 1000).toISOString(),
    persona: {
      id: String(c['sub']),
      subject: String(c['preferred_username'] ?? c['sub']),
      tenantId: String(c['tenant_id'] ?? ''),
      roles,
      label: String(c['name'] ?? c['preferred_username'] ?? 'Signed in'),
    },
  };
}

async function discover(issuer: string): Promise<Discovery> {
  const res = await fetch(issuer.replace(/\/$/, '') + '/.well-known/openid-configuration');
  if (!res.ok) throw new Error('Identity provider discovery failed (' + res.status + ')');
  return res.json();
}

function redirectUri(): string {
  return window.location.origin + CALLBACK_PATH;
}

export async function startLogin(issuer: string, clientId: string): Promise<void> {
  const d = await discover(issuer);
  const pending: Pending = { issuer, clientId, verifier: randomString(48), state: randomString(16) };
  sessionStorage.setItem(PENDING, JSON.stringify(pending));
  const q = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: redirectUri(),
    scope: 'openid',
    state: pending.state,
    code_challenge: await codeChallenge(pending.verifier),
    code_challenge_method: 'S256',
  });
  window.location.assign(d.authorization_endpoint + '?' + q.toString());
}

/** Handles the redirect back from the IdP: checks state, exchanges the code with the verifier. */
export async function completeLogin(search: string): Promise<Session> {
  const params = new URLSearchParams(search);
  const raw = sessionStorage.getItem(PENDING);
  sessionStorage.removeItem(PENDING); // single use, whatever happens next
  if (params.get('error')) throw new Error('Sign-in failed: ' + params.get('error'));
  if (!raw) throw new Error('No sign-in in progress');
  const pending = JSON.parse(raw) as Pending;
  if (!params.get('code') || params.get('state') !== pending.state) throw new Error('Sign-in state mismatch');

  const d = await discover(pending.issuer);
  const res = await fetch(d.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code: params.get('code')!,
      redirect_uri: redirectUri(),
      client_id: pending.clientId,
      code_verifier: pending.verifier,
    }),
  });
  if (!res.ok) throw new Error('Token exchange failed (' + res.status + ')');
  const t = await res.json();
  return sessionFromTokens(t.access_token, t.id_token, t.expires_in ?? 300);
}

/** Ends the IdP session too (otherwise "Sign out" would silently sign back in on the next click). */
export async function idpLogoutUrl(issuer: string, idToken: string | undefined): Promise<string | null> {
  const d = await discover(issuer);
  if (!d.end_session_endpoint) return null;
  const q = new URLSearchParams({ post_logout_redirect_uri: window.location.origin + '/login' });
  if (idToken) q.set('id_token_hint', idToken);
  return d.end_session_endpoint + '?' + q.toString();
}
