if (process.env.FINDWORKS_RUNTIME_ROLE === "reviewer") {
  await import("./reviewer-runner.mjs");
} else {
  await import("./runner.mjs");
}
