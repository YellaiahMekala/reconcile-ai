# reconcile-ai UI (Angular 20)

Investigation and approval console for reconcile-ai. Standalone components, signals, RxJS, no UI library.

| Screen | Who | What it shows |
|---|---|---|
| Sign in | everyone | Pick a demo persona (analyst, L1, L2, SRE admin, analyst in another tenant) |
| Breaks | all roles | Tenant's breaks; analysts can create one |
| Break workspace | all roles | Evidence upload and status, hybrid search with fused/vector/keyword scores, AI investigation with **live SSE stage progress**, cited proposal with route and citation check, hash-chained audit trail |
| Approvals | L1 / L2 | Queue for your tier; approve or reject with a comment (maker-checker enforced by the API) |
| Audit | SRE admin | Verify the whole audit hash chain |

The UI never decides access. It hides actions a persona cannot take, but every rule (401, 403, cross-tenant 404,
maker-checker, tier) is enforced by the API, and API errors are shown as returned.

## Run

```powershell
# terminal 1: database + API with the demo-login profile (persona tokens signed by the throwaway dev key)
docker compose -f docker/docker-compose.yml up -d
$env:OPENAI_API_KEY = "<key>"      # embeddings for evidence ingestion
mvn spring-boot:run "-Dspring-boot.run.profiles=mock-llm,demo-login"

# terminal 2: UI on http://localhost:4200 (proxies /api and /dev to :8080, so no CORS is needed)
cd ui
npm ci
npm start
```

Demo flow: sign in as **Recon analyst**, create a break (50500.00 vs 50000.00 USD), upload
`samples/evidence/bank-statement-BRK-3001.csv` or a fixture from `src/test/resources/fixtures/evidence/`, run the
investigation and watch the stages, then sign in as **L1 approver** and approve. Sign in as **Analyst in another
tenant** and open the same break URL: 404.

## Test

```powershell
npx ng build
npx ng test --watch=false --browsers=ChromeHeadless
```

Specs: SSE frame parser, auth interceptor (bearer only on `/api`, 401 ends the session), status badge tones, app shell.

## Why fetch() for SSE

`EventSource` cannot send an `Authorization` header. `core/sse.ts` reads the stream with `fetch` and parses frames;
unsubscribing aborts the request. If the stream drops, the workspace falls back to one REST read of the job.
