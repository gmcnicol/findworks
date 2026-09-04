import { mkdirSync, writeFileSync } from "node:fs";

export function preparePiEnvironment(environment = process.env, directory = "/tmp/pi-agent") {
  const piEnv = { ...environment };
  if (!piEnv.FINDWORKS_MODEL_OAUTH_B64) return piEnv;
  const auth = Buffer.from(piEnv.FINDWORKS_MODEL_OAUTH_B64, "base64");
  JSON.parse(auth.toString("utf8"));
  mkdirSync(directory, { recursive: true });
  writeFileSync(`${directory}/auth.json`, auth, { mode: 0o600 });
  delete piEnv.FINDWORKS_MODEL_OAUTH_B64;
  piEnv.PI_CODING_AGENT_DIR = directory;
  return piEnv;
}
