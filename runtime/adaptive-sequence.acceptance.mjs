import { runCli } from "./evaluation/cli.mjs";

runCli().then(code => {
  process.exitCode = code;
}).catch(error => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 2;
});
