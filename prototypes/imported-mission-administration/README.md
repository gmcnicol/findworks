# Imported Mission administration prototype

> THROWAWAY PROTOTYPE for the Wayfinder ticket **Shape imported Mission administration in the web app**.

## Question

How should FindWorks present and administer an MCP-imported draft Interview Mission without reintroducing Investigator grilling or hiding exact-version authority?

## Shape

Three structurally different variants of the same Mission administration route, switchable with `?variant=`:

- `A` — Focused review
- `B` — Source-aware brief
- `C` — Lifecycle-led administration

Each variant supports six representative scenarios: draft review, approved, invited, active Interview Session, findings ready, and safe recovery.

This is a standalone static prototype because `main` intentionally contains no product implementation. It uses in-memory sample data and performs no real mutations.

## Run

From the repository root:

```bash
python3 prototypes/imported-mission-administration/serve.py
```

Open `http://<server-address>:4173/?variant=A&scenario=draft`. The server binds to all interfaces by default; set `PROTOTYPE_BIND` to restrict it to one interface.

Use the fixed prototype bar or the left/right arrow keys to switch variants. The URL remains shareable.
