import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { StringEnum } from "@earendil-works/pi-ai";
import { Type } from "typebox";

const uuid = Type.String({ format: "uuid" });
const question = Type.String({ minLength: 1, maxLength: 2000 });
const context = Type.Optional(Type.String({ minLength: 1, maxLength: 2000 }));
const progress = Type.Object({
  covered: Type.String({ minLength: 1, maxLength: 2000 }),
  current: Type.String({ minLength: 1, maxLength: 2000 }),
  remaining: Type.String({ minLength: 1, maxLength: 2000 }),
});

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "submit_interview_turn",
    label: "Submit Interview turn",
    description: "Submit one validated semantic Interview turn to FindWorks.",
    parameters: Type.Object({
      runId: uuid,
      sessionId: uuid,
      expectedRevision: Type.Integer({ minimum: 1 }),
      outcomes: Type.Array(Type.Object({
        kind: StringEnum(["supported_knowledge", "assumption", "unknown", "conflict", "ownership_gap"] as const),
        investigationItemId: uuid,
        evidenceIds: Type.Array(uuid, { maxItems: 10 }),
        reason: Type.Optional(StringEnum(["did_not_know", "declined", "evidence_insufficient", "owner_unidentified"] as const)),
        explanation: Type.Optional(Type.String({ minLength: 1, maxLength: 4000 })),
        knowledgeKind: Type.Optional(StringEnum(["fact", "rule", "decision", "term", "exception", "assumption"] as const)),
        claim: Type.Optional(Type.String({ minLength: 1, maxLength: 4000 })),
        unresolvedSubject: Type.Optional(Type.String({ minLength: 1, maxLength: 2000 })),
        whyCurrentParticipantCannotAnswer: Type.Optional(Type.String({ minLength: 1, maxLength: 2000 })),
        ownerName: Type.Optional(Type.String({ minLength: 1, maxLength: 300 })),
        ownerDescription: Type.Optional(Type.String({ minLength: 1, maxLength: 1000 })),
        conflictMembers: Type.Optional(Type.Array(Type.Object({
          claim: Type.String({ minLength: 1, maxLength: 4000 }),
          evidenceId: uuid,
        }), { minItems: 2, maxItems: 10 })),
      }), { maxItems: 10 }),
      scopeAssessments: Type.Array(Type.Object({
        evidenceId: uuid,
        assessment: StringEnum(["in_scope", "out_of_scope"] as const),
        missionBoundaryId: Type.Optional(uuid),
        rationale: Type.String({ minLength: 1, maxLength: 2000 }),
      }), { maxItems: 10 }),
      nextAction: Type.Union([
        Type.Object({
          kind: StringEnum(["ask_question"] as const),
          targetInvestigationItemId: uuid,
          question,
          humanContext: context,
          progress,
        }),
        Type.Object({
          kind: StringEnum(["ask_clarification"] as const),
          targetInvestigationItemId: uuid,
          question,
          humanContext: context,
          sourceQuestionId: uuid,
          progress,
        }),
        Type.Object({
          kind: StringEnum(["ask_paraphrase_confirmation"] as const),
          targetInvestigationItemId: uuid,
          question,
          humanContext: context,
          sourceEvidenceId: uuid,
          paraphraseReason: StringEnum(["ambiguity", "contradiction", "inference", "material_importance"] as const),
          progress,
        }),
        Type.Object({
          kind: StringEnum(["propose_completion"] as const),
          completionRecap: Type.String({ minLength: 1, maxLength: 2000 }),
          unresolvedReferences: Type.Array(Type.Object({
            investigationItemId: uuid,
            kind: StringEnum(["unknown", "conflict", "ownership_gap"] as const),
          }), { maxItems: 50 }),
        }),
      ]),
    }),
    async execute(_toolCallId, submission) {
      return {
        content: [{ type: "text", text: "Interview turn accepted for FindWorks validation." }],
        details: { submission },
        terminate: true,
      };
    },
  });
}
