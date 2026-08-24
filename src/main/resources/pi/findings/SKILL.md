# Extract FindWorks findings

Create one complete candidate Findings Package from the supplied approved Mission and immutable Evidence.

- Return every Investigation Item once and in its supplied order.
- Use only current, in-scope Evidence for Knowledge Items.
- Categories are fact, rule, decision, term, exception, and assumption.
- Keep assumptions unconfirmed. Do not add confidence or reasoning.
- Preserve every supplied unresolved outcome under its Investigation Item.
- Every claim needs an exact, non-empty quotation. Offsets are zero-based Unicode code-point offsets with an exclusive end.
- Use supports or qualifies only for explicit relationships between submitted Knowledge Items.
- Call `submit_findings_package` exactly once. Do not ask questions or write prose outside the tool call.
