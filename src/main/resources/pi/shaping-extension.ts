import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { StringEnum } from "@earendil-works/pi-ai";
import { Type } from "typebox";

const source = Type.Object({
  kind: StringEnum(["investigator_message", "agent_proposal"] as const),
  messageIds: Type.Array(Type.String({ format: "uuid" }), { maxItems: 50 }),
});

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "ask_shaping_question",
    label: "Ask shaping question",
    description: "Return one plain-language follow-up that depends on the Investigator's latest statement.",
    parameters: Type.Object({
      question: Type.String({ minLength: 1, maxLength: 2000 }),
    }),
    async execute(_toolCallId, { question }) {
      return {
        content: [{ type: "text", text: "Question accepted by FindWorks." }],
        details: { question },
        terminate: true,
      };
    },
  });

  pi.registerTool({
    name: "propose_interview_mission",
    label: "Propose Interview Mission",
    description: "Return a complete, provenance-backed Interview Mission proposal for Investigator review.",
    parameters: Type.Object({
      objective: Type.Object({ value: Type.String({ minLength: 1, maxLength: 4000 }), source }),
      desiredOutcome: Type.Object({ value: Type.String({ minLength: 1, maxLength: 4000 }), source }),
      intendedInterviewee: Type.Object({ value: Type.String({ minLength: 1, maxLength: 1000 }), source }),
      intervieweeRelevance: Type.Object({ value: Type.String({ minLength: 1, maxLength: 4000 }), source }),
      contexts: Type.Array(Type.Object({
        visibility: StringEnum(["shared", "private"] as const),
        content: Type.String({ minLength: 1, maxLength: 4000 }),
        source,
      }), { minItems: 1, maxItems: 30 }),
      boundaries: Type.Array(Type.Object({
        kind: StringEnum(["boundary", "prohibited_topic"] as const),
        content: Type.String({ minLength: 1, maxLength: 4000 }),
        source,
      }), { minItems: 2, maxItems: 30 }),
      terminology: Type.Array(Type.Object({
        term: Type.String({ minLength: 1, maxLength: 300 }),
        meaning: Type.String({ minLength: 1, maxLength: 2000 }),
        source,
      }), { maxItems: 30 }),
      openingQuestions: Type.Array(Type.Object({
        question: Type.String({ minLength: 1, maxLength: 2000 }),
        source,
      }), { minItems: 1, maxItems: 20 }),
      completionCriteria: Type.Object({ value: Type.String({ minLength: 1, maxLength: 4000 }), source }),
      expectedCommitment: Type.Object({ value: Type.String({ minLength: 1, maxLength: 1000 }), source }),
      dataUseSummary: Type.Object({ value: Type.String({ minLength: 1, maxLength: 4000 }), source }),
      investigationItems: Type.Array(Type.Object({
        knowledgeGap: Type.String({ minLength: 1, maxLength: 4000 }),
        importance: Type.String({ minLength: 1, maxLength: 4000 }),
        priority: StringEnum(["high", "medium", "low"] as const),
        relevantContext: Type.String({ minLength: 1, maxLength: 4000 }),
        required: Type.Boolean(),
        allowedOutcomes: Type.Array(Type.Object({
          kind: StringEnum(["supported_knowledge", "unknown", "conflict", "ownership_gap"] as const),
          source,
        }), { minItems: 1, maxItems: 4 }),
        source,
      }), { minItems: 1, maxItems: 50 }),
      unresolvedAmbiguities: Type.Array(Type.Object({
        content: Type.String({ minLength: 1, maxLength: 4000 }),
        representedByItemPosition: Type.Optional(Type.Integer({ minimum: 0 })),
        source,
      }), { maxItems: 30 }),
    }),
    async execute(_toolCallId, proposal) {
      return {
        content: [{ type: "text", text: "Interview Mission proposal accepted by FindWorks." }],
        details: { proposal },
        terminate: true,
      };
    },
  });
}
