import { mkdirSync, writeFileSync } from "node:fs";

export function preparePiEnvironment(environment = process.env, directory = "/tmp/pi-agent") {
  const piEnv = { ...environment, PI_CODING_AGENT_DIR: directory };
  mkdirSync(directory, { recursive: true });
  const resolvedModel = resolvedOpenAiModel(piEnv.FINDWORKS_MODEL_PROVIDER, piEnv.FINDWORKS_MODEL);
  if (resolvedModel) {
    writeFileSync(`${directory}/models.json`, JSON.stringify({
      providers: {
        openai: {
          baseUrl: "https://api.openai.com/v1",
          models: [resolvedModel],
        },
      },
    }), { mode: 0o600 });
  }
  if (!piEnv.FINDWORKS_MODEL_OAUTH_B64) return piEnv;
  const auth = Buffer.from(piEnv.FINDWORKS_MODEL_OAUTH_B64, "base64");
  JSON.parse(auth.toString("utf8"));
  writeFileSync(`${directory}/auth.json`, auth, { mode: 0o600 });
  delete piEnv.FINDWORKS_MODEL_OAUTH_B64;
  return piEnv;
}

function resolvedOpenAiModel(provider, model) {
  if (provider !== "openai" || typeof model !== "string") return null;
  const mini = model.match(/^gpt-5\.4-mini-(\d{4}-\d{2}-\d{2})$/);
  if (mini) return {
    id: model,
    name: `GPT-5.4 mini (${mini[1]})`,
    api: "openai-responses",
    reasoning: true,
    input: ["text", "image"],
    cost: { input: 0.75, output: 4.5, cacheRead: 0.075, cacheWrite: 0 },
    contextWindow: 400000,
    maxTokens: 128000,
    thinkingLevelMap: thinkingLevelMap(),
  };
  const full = model.match(/^gpt-5\.4-(\d{4}-\d{2}-\d{2})$/);
  if (full) return {
    id: model,
    name: `GPT-5.4 (${full[1]})`,
    api: "openai-responses",
    reasoning: true,
    input: ["text", "image"],
    cost: {
      input: 2.5,
      output: 15,
      cacheRead: 0.25,
      cacheWrite: 0,
      tiers: [{ inputTokensAbove: 272000, input: 5, output: 22.5, cacheRead: 0.5, cacheWrite: 0 }],
    },
    contextWindow: 272000,
    maxTokens: 128000,
    thinkingLevelMap: thinkingLevelMap(),
  };
  return null;
}

function thinkingLevelMap() {
  return { off: "none", minimal: null, low: "low", medium: "medium", high: "high", xhigh: "xhigh", max: null };
}
