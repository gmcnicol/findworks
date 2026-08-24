import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { StringEnum } from "@earendil-works/pi-ai";
import { Type } from "typebox";

const uuid = Type.String({ format: "uuid" });

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "submit_findings_package",
    label: "Submit Findings Package",
    description: "Submit one complete, provenance-linked Findings Package for FindWorks validation.",
    parameters: Type.Object({
      runId: uuid,
      sessionId: uuid,
      expectedRevision: Type.Integer({ minimum: 1 }),
      groups: Type.Array(Type.Object({
        investigationItemId: uuid,
        knowledgeItems: Type.Array(Type.Object({
          id: uuid,
          category: StringEnum(["fact", "rule", "decision", "term", "exception", "assumption"] as const),
          claim: Type.String({ minLength: 1, maxLength: 4000 }),
          citations: Type.Array(Type.Object({
            evidenceId: uuid,
            startOffset: Type.Integer({ minimum: 0 }),
            endOffset: Type.Integer({ minimum: 1 }),
            quotation: Type.String({ minLength: 1, maxLength: 10000 }),
          }), { minItems: 1, maxItems: 20 }),
          links: Type.Array(Type.Object({
            kind: StringEnum(["supports", "qualifies"] as const),
            targetKnowledgeItemId: uuid,
          }), { maxItems: 20 }),
        }), { maxItems: 50 }),
        unresolvedOutcomeIds: Type.Array(uuid, { maxItems: 50 }),
      }), { minItems: 1, maxItems: 100 }),
    }),
    async execute(_toolCallId, submission) {
      return {
        content: [{ type: "text", text: "Findings Package accepted for FindWorks validation." }],
        details: { submission },
        terminate: true,
      };
    },
  });
}
