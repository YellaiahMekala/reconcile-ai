# scripts/

## generate-local-jwt.ps1
Mints an RS256 JWT for hitting the local API, signed with the dev keypair under
`scripts/jwt/` (public half at `src/main/resources/jwt/dev-public.pem`, which the
running app validates against).

**One-time prerequisite:** compile the test sources so the minter class exists:
```powershell
mvn test-compile
```
Then:
```powershell
$JWT = .\scripts\generate-local-jwt.ps1 -Tenant TENANT_A -Role RECON_ANALYST
curl -X POST http://localhost:8080/api/v1/breaks `
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" `
  -d '{\"breakReference\":\"BRK-0001\",\"ledgerAmount\":\"50500.00\",\"externalAmount\":\"50000.00\",\"currency\":\"USD\"}'
```

## jwt/
Throwaway RSA dev keypair. **NOT a secret** — regenerate anytime with:
```bash
openssl genrsa -out scripts/jwt/dev-private.pem 2048
openssl rsa -in scripts/jwt/dev-private.pem -pubout -out src/main/resources/jwt/dev-public.pem
openssl pkcs8 -topk8 -inform PEM -in scripts/jwt/dev-private.pem -out scripts/jwt/dev-private-pkcs8.pem -nocrypt
```
Never use these keys anywhere but local development.
