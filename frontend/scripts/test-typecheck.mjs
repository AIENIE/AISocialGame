import { mkdtemp, readFile, writeFile, rm } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import path from "node:path";
import os from "node:os";
import ts from "typescript";
import { fileURLToPath } from "node:url";

// Run the same public entry point as local/CI, using isolated tsconfig fixtures.
const directory = await mkdtemp(path.join(os.tmpdir(), "aisocialgame-typecheck-"));
const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
const commands = packageJson.scripts.typecheck.split(" && ");
if (commands.length !== 2) throw new Error("Typecheck must cover application and build configuration");
try {
  for (const command of commands) {
    const [, , , configName] = command.split(" ");
    const { config, error } = ts.readConfigFile(fileURLToPath(new URL(`../${configName}`, import.meta.url)), ts.sys.readFile);
    if (error) throw new Error(ts.flattenDiagnosticMessageText(error.messageText, "\n"));
    const source = path.join(directory, "invalid.ts");
    const fixture = path.join(directory, "tsconfig.json");
    await writeFile(source, 'export const invalid: string = 123;\n');
    await writeFile(fixture, JSON.stringify({ compilerOptions: config.compilerOptions, files: [source] }));
    const result = spawnSync(process.execPath, ["node_modules/typescript/bin/tsc", "--noEmit", "-p", fixture], { encoding: "utf8" });
    if (result.status === 0 || !result.stdout.includes("TS2322")) throw new Error(`${configName} failed to detect a real type error: ${result.stdout}${result.stderr}`);
    console.log(`${configName}: negative fixture rejected`);
  }
} finally {
  await rm(directory, { recursive: true, force: true });
}
