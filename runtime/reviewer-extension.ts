import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { submitTurn } from "./turn-client.mjs";

const url = process.env.FINDWORKS_REVIEW_URL;
const token = process.env.FINDWORKS_REVIEW_TOKEN;
if (!url || !token) throw new Error("Missing review capability");

const evidenceIds = Type.Array(Type.String());

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "submit_investigation_review",
    label: "Submit investigation review",
    description: "Submit one advisory, Evidence-linked review proposal. The server validates it before it can enter interviewer context.",
    parameters: Type.Object({
      schemaVersion: Type.Literal(1),
      requestId: Type.String(),
      proposalId: Type.String({ description: "Use proposal-<requestId> so an identical request has one stable proposal identity." }),
      sessionId: Type.String(),
      missionVersion: Type.Integer(),
      expectedRevision: Type.Integer(),
      evidenceRevision: Type.String(),
      outcomeProposals: Type.Array(Type.Object({
        resultId: Type.String(),
        coverage: Type.Union([
          Type.Literal("SUPPORTED"), Type.Literal("UNKNOWN"), Type.Literal("CONFLICT"),
          Type.Literal("ASSUMPTION"), Type.Literal("OWNERSHIP_GAP"),
        ]),
        category: Type.Optional(Type.Union([
          Type.Literal("FACT"), Type.Literal("RULE"), Type.Literal("DECISION"),
          Type.Literal("TERM"), Type.Literal("EXCEPTION"), Type.Literal("ASSUMPTION"),
        ])),
        evidenceIds,
        summary: Type.String(),
      })),
      nextThreadProposal: Type.Union([
        Type.Null(),
        Type.Object({
          resultId: Type.String(),
          reason: Type.String(),
          evidenceIds,
        }),
      ]),
      completionAssessment: Type.Object({
        status: Type.Union([Type.Literal("READY"), Type.Literal("NOT_READY")], { description: "After projecting outcomeProposals, READY iff no required result is UNADDRESSED, EXPLORING, or PARTIAL. CONFLICT and every other terminal outcome count as complete." }),
        openRequiredResultIds: Type.Array(Type.String(), { description: "Exactly every required result whose projected coverage after outcomeProposals is UNADDRESSED, EXPLORING, or PARTIAL; empty for READY." }),
        reason: Type.String(),
        evidenceIds,
      }),
    }),
    async execute(_toolCallId, params) {
      const body = await submitTurn({ url, token, params });
      return { content: [{ type: "text", text: body }], details: {}, terminate: true };
    },
  });
}
