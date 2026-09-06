import assert from "node:assert/strict";
import http from "node:http";
import test from "node:test";
import { submitTurn } from "./turn-client.mjs";

test("semantic rejection exposes only its stable correction code", async () => {
  const server = http.createServer((_request, response) => {
    response.writeHead(400, { "content-type": "application/json" });
    response.end(JSON.stringify({ code: "unsubstantiated_unresolved_outcome", private_detail: "do not expose" }));
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
    const { port } = server.address();
    await assert.rejects(
      submitTurn({ url: `http://127.0.0.1:${port}`, token: "synthetic", params: {} }),
      error => error.message === "Turn rejected: unsubstantiated_unresolved_outcome",
    );
  } finally {
    server.close();
  }
});
