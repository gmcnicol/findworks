# Mission submission contract

Use contract version `1`. Submit a complete snapshot, never a patch.

## Discovery target

For `create_discovery_with_draft_mission`, include:

- `discovery_title`: human-confirmed title;
- `discovery_objective`: longer-lived Discovery objective.

For `add_draft_mission`, include only `discovery_id` from `list_discoveries`.

Organisation, owner, lifecycle, version, recipient, and approval are server-owned.

## Mission

`mission` contains:

- `objective` and `desired_outcome`;
- `intended_interviewee_role` and `intended_interviewee_relevance`;
- one or more `investigation_items`;
- explicit arrays for `shared_context`, `boundaries`, `prohibited_topics`, and `terminology`, including when empty;
- one or more `opening_questions` and `completion_criteria`;
- `expected_commitment_minutes` from 5 through 120;
- `data_use_summary`, including reviewed-context export and the fact that later deletion cannot recall downstream copies.

Each Investigation Item contains `knowledge_gap`, `why_it_matters`, `priority`, `required`, `relevant_context`, and one or more `sufficient_evidence` descriptions. `terminology` entries contain `term` and `meaning`.

## Authority

`origins` binds every semantic scalar and list entry by JSON Pointer. Authority is exactly one of:

- `INVESTIGATOR_STATEMENT`;
- `CONFIRMED_AGENT_PROPOSAL`.

Bind an Investigation Item or terminology object at its list-entry pointer. Bind other list entries at their item pointer. The contract rejects missing, duplicate, or unsupported bindings.

## Project references

`project_references` contains locators only:

- `repository_file`: safe relative `locator`, optional `revision`, `line_start`, and `line_end`;
- `issue`: HTTP or HTTPS issue `locator`, optional `revision`.

Never include source content. Private context has no locator in the submitted payload.

## Confirmations

All must be true:

```json
{
  "exact_payload_reviewed": true,
  "intentional_sharing": true,
  "no_recipient_or_private_context": true,
  "material_agent_proposals_confirmed": true
}
```

Unknown properties are rejected. `submission_id` is a client-generated UUID. Reuse it only for an exact retry of the same payload.
