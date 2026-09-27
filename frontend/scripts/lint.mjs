import { ESLint } from "eslint";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const eslint = new ESLint();
const results = await eslint.lintFiles(["."]);
const warnings = {};
let errors = 0;
for (const result of results) {
  errors += result.errorCount;
  for (const message of result.messages.filter((item) => item.severity === 1)) {
    const key = `${path.relative(process.cwd(), result.filePath).replaceAll("\\", "/")}:${message.ruleId}`;
    warnings[key] = (warnings[key] || 0) + 1;
  }
}
const baselineUrl = new URL("../lint-baseline.json", import.meta.url);
if (process.argv.includes("--write-baseline")) {
  if (errors) throw new Error("Cannot baseline lint errors");
  await writeFile(baselineUrl, JSON.stringify(Object.fromEntries(Object.entries(warnings).sort()), null, 2) + "\n");
} else {
  const baseline = JSON.parse(await readFile(baselineUrl, "utf8"));
  const additions = Object.entries(warnings).filter(([key, count]) => count > (baseline[key] || 0));
  if (errors || additions.length) {
    console.error(await (await eslint.loadFormatter("stylish")).format(results));
    console.error("New warning groups:", additions);
    process.exitCode = 1;
  } else {
    console.log(`Lint passed: no errors or new warnings (${Object.values(warnings).reduce((a, b) => a + b, 0)} existing).`);
  }
}
