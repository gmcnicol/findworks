import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

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
}
