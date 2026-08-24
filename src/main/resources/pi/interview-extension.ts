import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const uuid = Type.String({ format: "uuid" });

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
        kind: Type.String(),
        investigationItemId: uuid,
      }), { maxItems: 0 }),
      nextAction: Type.Object({
        kind: Type.Literal("ask_question"),
        targetInvestigationItemId: uuid,
        question: Type.String({ minLength: 1, maxLength: 2000 }),
        humanContext: Type.Optional(Type.String({ minLength: 1, maxLength: 2000 })),
        progress: Type.Object({
          covered: Type.String({ minLength: 1, maxLength: 2000 }),
          current: Type.String({ minLength: 1, maxLength: 2000 }),
          remaining: Type.String({ minLength: 1, maxLength: 2000 }),
        }),
      }),
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
