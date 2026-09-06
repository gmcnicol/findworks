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

  observe(event) {
    if (event.type === "response" && event.command === "set_auto_retry" && !event.success) {
      return terminal(64, "RETRY_POLICY_REJECTED");
    }
    if (event.type === "response" && event.command === "prompt" && !event.success) {
      return terminal(64, "PROMPT_REJECTED");
    }
    if (event.type === "message_end" && event.message?.role === "assistant" && event.message.stopReason === "error") {
      this.#providerError = true;
    }
    if (event.type === "tool_execution_end" && event.toolName === "submit_interview_turn" && !event.isError) {
      this.#semanticCommit = true;
    }
    if (event.type === "agent_settled") {
      if (this.#semanticCommit) return terminal(0, "COMMITTED");
      if (this.#providerError) return terminal(75, "PROVIDER_ERROR");
      return terminal(70, "NO_SEMANTIC_COMMIT");
    }
    return { terminal: false };
  }
}

function terminal(exitCode, reason) {
  return { terminal: true, exitCode, reason };
}
