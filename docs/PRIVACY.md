# Privacy Policy — AI assistant context for this project

This is the standing privacy policy for work in `reconcile-ai` (Phase 1). It applies to Cursor, Codex, Copilot, IntelliJ AI, and any other assistant using this workspace.

Machine-readable copies:

- `.cursor/rules/privacy.mdc` (always-on Cursor rule)
- `.codex/rules/privacy.md` (Codex / AGENTS path)

## Purpose

Interview prep and code work in this repo may include career context (roles, employers, skills, projects). Direct personal identifiers must not be stored, quoted, or written into generated artifacts.

## Rules

1. **Never store, quote, or output** name, email, phone, postal address, PAN/tax IDs, government IDs, login handles, GitHub handles, LinkedIn handles, or other direct identity fields.
2. If identity fields appear in files, chat, attachments, logs, or generated output, **ignore and omit them** unless the user explicitly requests a named CV artifact in the current turn.
3. Refer to the user as **“you”** or **“the engineer”**, never by name.
4. Use **role, skills, employers, domains, projects, technologies, and interview positioning** only.
5. **Do not add PII** to commits, docs, logs, READMEs, prompts, examples, test data, or generated artifacts.
6. **Never expose secrets.** Use environment variables, secret stores, or placeholder names (for example `JWT_PUBLIC_KEY`, `DB_PASSWORD`). Dev RSA keys in this repo are throwaway local-dev material; do not treat them as production secrets, and do not copy them into public write-ups as if they were live credentials.

## Allowed context (non-PII)

Assistants may use:

- Target roles (Senior/Lead Java AI Cloud Engineer, Java AI Backend Integration Engineer, Java GenAI Platform Engineer)
- Skills and stack (Java 21, Spring Boot 3.x, Spring AI, LangChain4j, RAG, Kafka, etc.)
- Employer names and domains already used as professional history (without attaching personal contact details)
- Project facts from `README.md`

## Project data classification

This application is a **portfolio/demonstration** system. It uses **synthetic data only** — no production or client data. Evidence uploads in Phase 1 are hashed; do not put real account numbers, PAN, or customer PII into examples or tests.
