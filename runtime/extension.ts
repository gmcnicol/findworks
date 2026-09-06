import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { submitTurn } from "./turn-client.mjs";

const url = process.env.FINDWORKS_TURN_URL;
const token = process.env.FINDWORKS_TURN_TOKEN;
if (!url || !token) throw new Error("Missing turn capability");

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "submit_interview_turn",
    label: "Submit interview turn",
    description: "Atomically propose Evidence-linked outcomes and exactly one next interview action.",
    parameters: Type.Object({
      runId: Type.String(),
      expectedRevision: Type.Integer(),
      outcomes: Type.Array(Type.Object({
        resultId: Type.String(),
        coverage: Type.Union([
          Type.Literal("SUPPORTED"), Type.Literal("UNKNOWN"), Type.Literal("CONFLICT"),
          Type.Literal("ASSUMPTION"), Type.Literal("OWNERSHIP_GAP"),
        ]),
        category: Type.Optional(Type.Union([
          Type.Literal("FACT"), Type.Literal("RULE"), Type.Literal("DECISION"),
          Type.Literal("TERM"), Type.Literal("EXCEPTION"), Type.Literal("ASSUMPTION"),
        ])),
        evidenceIds: Type.Array(Type.String()),
        summary: Type.String(),
      })),
      nextAction: Type.Object({
        type: Type.Union([Type.Literal("ASK_QUESTION"), Type.Literal("PROPOSE_COMPLETION")]),
        resultId: Type.Optional(Type.String()),
        text: Type.Optional(Type.String()),
        responseMode: Type.Optional(Type.Union([
          Type.Literal("FREE_TEXT"), Type.Literal("YES_NO"), Type.Literal("YES_NO_PARTLY"),
          Type.Literal("PARAPHRASE"), Type.Literal("CHOICE"),
        ])),
        options: Type.Optional(Type.Array(Type.Object({ id: Type.String(), label: Type.String() }), { minItems: 2, maxItems: 5 })),
      }),
    }),
    async execute(_toolCallId, params) {
      const body = await submitTurn({ url, token, params });
      return { content: [{ type: "text", text: body }], details: {}, terminate: true };
    },
  });
}
