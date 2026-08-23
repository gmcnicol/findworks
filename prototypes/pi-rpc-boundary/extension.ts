import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";

const required = (name: string) => {
  const value = process.env[name];
  if (!value) throw new Error(`Missing ${name}`);
  return value;
};

const store = required("FINDWORKS_PROTOTYPE_STORE");
const sessionId = required("FINDWORKS_SESSION_ID");
const missionMarker = required("FINDWORKS_MISSION_MARKER");
const credentialMarker = required("FINDWORKS_CREDENTIAL_MARKER");

const append = (value: unknown) => appendFileSync(store, `${JSON.stringify(value)}\n`);

export default function (pi: ExtensionAPI) {
  pi.on("session_start", async () => {
    append({
      kind: "resource_audit",
      sessionId,
      activeTools: pi.getActiveTools().sort(),
      allTools: pi.getAllTools().map((tool) => tool.name).sort(),
    });
  });

  pi.registerTool({
    name: "ask_question",
    label: "Ask question",
    description: "Commit one plain-language interview question to FindWorks.",
    parameters: Type.Object({
      operationKey: Type.String(),
      sessionId: Type.String(),
      missionMarker: Type.String(),
      question: Type.String(),
    }),
    async execute(_toolCallId, params) {
      if (params.sessionId !== sessionId || params.missionMarker !== missionMarker) {
        throw new Error("Session or Mission scope denied");
      }

      const records = existsSync(store)
        ? readFileSync(store, "utf8").trim().split("\n").filter(Boolean).map(JSON.parse)
        : [];
      const existing = records.find(
        (record) => record.kind === "question_commit" && record.operationKey === params.operationKey,
      );
      if (!existing) {
        append({
          kind: "question_commit",
          operationKey: params.operationKey,
          sessionId,
          missionMarker,
          credentialMarker,
          question: params.question,
        });
      }

      const crashMarker = `${store}.${params.operationKey}.crashed`;
      if (process.env.FINDWORKS_CRASH_AFTER_COMMIT === params.operationKey && !existsSync(crashMarker)) {
        writeFileSync(crashMarker, "crashed before tool result\n");
        process.kill(process.pid, "SIGKILL");
      }

      return {
        content: [{ type: "text", text: JSON.stringify(existing ?? params) }],
        details: {},
        terminate: true,
      };
    },
  });

  pi.registerTool({
    name: "reject_invalid_effect",
    label: "Reject invalid effect",
    description: "Exercise a recoverable semantic-tool failure without changing application state.",
    parameters: Type.Object({ reason: Type.String() }),
    async execute() {
      throw new Error("Prototype semantic validation rejected effect");
    },
  });
}
