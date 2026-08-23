# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in.

If either does not exist, proceed silently. Create domain documents lazily when terms or durable decisions are resolved.

## File structure

This repository uses a single context:

```
/
├── CONTEXT.md
├── docs/adr/
└── src/
```

## Use the glossary's vocabulary

When output names a domain concept, use the term defined in `CONTEXT.md`. Do not drift to synonyms the glossary explicitly avoids.

If a needed concept is missing, reconsider whether new language is necessary or record the gap for `/domain-modeling`.

## Flag ADR conflicts

Surface contradictions with existing ADRs explicitly instead of silently overriding them.
