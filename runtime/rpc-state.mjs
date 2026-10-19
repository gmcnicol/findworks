import { StringDecoder } from "node:string_decoder";

export class JsonlDecoder {
  #decoder = new StringDecoder("utf8");
  #buffer = "";

  push(chunk) {
    this.#buffer += this.#decoder.write(chunk);
    return this.#drain(false);
  }

  end(chunk) {
    if (chunk) this.#buffer += this.#decoder.write(chunk);
    this.#buffer += this.#decoder.end();
    return this.#drain(true);
  }

  #drain(flush) {
    const records = [];
    for (;;) {
      const newline = this.#buffer.indexOf("\n");
      if (newline < 0) break;
      const line = this.#buffer.slice(0, newline).replace(/\r$/, "");
      this.#buffer = this.#buffer.slice(newline + 1);
      if (line) records.push(JSON.parse(line));
    }
    if (flush && this.#buffer) {
      records.push(JSON.parse(this.#buffer.replace(/\r$/, "")));
      this.#buffer = "";
    }
    return records;
  }
}

export class RpcTurnMonitor {
  #providerError = false;
  #semanticCommit = false;
  #usage;
  #modelIdentity;
  #modelIdentityMismatch = false;
  #reasoningEffort;

  constructor(semanticToolName = "submit_interview_turn", { reasoningEffort } = {}) {
    this.semanticToolName = semanticToolName;
    this.#reasoningEffort = reasoningEffort ?? null;
  }

  observe(event) {
    if (event.type === "response" && event.command === "set_auto_retry" && !event.success) {
      return terminal(64, "RETRY_POLICY_REJECTED");
    }
    if (event.type === "response" && event.command === "prompt" && !event.success) {
      return terminal(64, "PROMPT_REJECTED");
    }
    if (event.type === "message_end" && event.message?.role === "assistant") {
      this.#addUsage(event.message.usage);
      this.#recordModelIdentity(event.message);
      if (event.message.stopReason === "error") this.#providerError = true;
    }
    if (event.type === "tool_execution_end" && event.toolName === this.semanticToolName && !event.isError) {
      this.#semanticCommit = true;
    }
    if (event.type === "agent_settled") {
      if (this.#semanticCommit) return terminal(0, "COMMITTED");
      if (this.#providerError) return terminal(75, "PROVIDER_ERROR");
      return terminal(70, "NO_SEMANTIC_COMMIT");
    }
    return { terminal: false };
  }

  usage() {
    return this.#usage ? {
      ...this.#usage,
      modelIdentity: this.#modelIdentityMismatch ? null : this.#modelIdentity,
    } : null;
  }

  #recordModelIdentity(message) {
    const identity = {
      provider: message.provider ?? null,
      requestedModel: message.model ?? null,
      responseModel: message.responseModel ?? message.model ?? null,
      reasoningEffort: this.#reasoningEffort,
    };
    if (!this.#modelIdentity) this.#modelIdentity = identity;
    else if (JSON.stringify(this.#modelIdentity) !== JSON.stringify(identity)) this.#modelIdentityMismatch = true;
  }

  #addUsage(usage) {
    if (!usage) return;
    this.#usage ??= { inputTokens: 0, outputTokens: 0, cacheWriteTokens: 0, costUsd: 0 };
    this.#usage.inputTokens += (usage.input ?? 0) + (usage.cacheRead ?? 0);
    this.#usage.outputTokens += usage.output ?? 0;
    this.#usage.cacheWriteTokens += usage.cacheWrite ?? 0;
    this.#usage.costUsd += usage.cost?.total ?? 0;
  }
}

function terminal(exitCode, reason) {
  return { terminal: true, exitCode, reason };
}
