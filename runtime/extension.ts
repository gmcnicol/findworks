import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { readFileSync } from "node:fs";
import { Type } from "typebox";
import { prepareInterviewProposal, validateInterviewProposal } from "./interview-strategy.mjs";
import { submitTurn } from "./turn-client.mjs";

const url = process.env.FINDWORKS_TURN_URL;
const token = process.env.FINDWORKS_TURN_TOKEN;
const contextPath = process.env.FINDWORKS_INTERVIEW_CONTEXT;
if (!url || !token || !contextPath) throw new Error("Missing turn capability or Interview context");
const context = JSON.parse(readFileSync(contextPath, "utf8"));

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
      }), { description: "Only new or materially changed outcomes compared with current_outcomes; not a full snapshot. Omit terminal outcomes for a result when nextAction asks a follow-up on that result." }),
      nextAction: Type.Union([
        Type.Object({
          type: Type.Literal("ASK_QUESTION"),
          resultId: Type.String(),
          text: Type.String({ description: "One natural, non-leading question with one information need, phrased in the interviewee's domain language. For free text, do not invent alternatives or possible answers." }),
          responseMode: Type.Optional(Type.Union([
            Type.Literal("FREE_TEXT"), Type.Literal("YES_NO"), Type.Literal("YES_NO_PARTLY"),
            Type.Literal("PARAPHRASE"), Type.Literal("CHOICE"),
          ], { description: "FREE_TEXT is the default; use a structured mode only for a genuinely finite decision or paraphrase confirmation." })),
          options: Type.Optional(Type.Array(Type.Object({
            id: Type.String(),
            label: Type.String({ description: "Use the interviewee's own wording for finite choices." }),
          }), { minItems: 2, maxItems: 5 })),
        }),
        Type.Object({ type: Type.Literal("PROPOSE_COMPLETION") }),
      ]),
    }),
    async execute(_toolCallId, params) {
      const prepared = prepareInterviewProposal(context, params);
      const violations = validateInterviewProposal(context, prepared);
      if (violations.length > 0) {
        throw new Error(`Turn strategy rejected: ${violations.map(value => `${value.code}: ${value.detail}`).join("; ")}`);
      }
      const body = await submitTurn({ url, token, params: prepared });
      return { content: [{ type: "text", text: body }], details: {}, terminate: true };
    },
  });
}
