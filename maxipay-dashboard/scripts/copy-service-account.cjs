/**
 * Ensures serviceAccountKey.json exists for Next/webpack (import in firebaseAdmin.ts).
 * Copies from the committed template once; replace with your real Firebase key locally / in CI.
 */
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const example = path.join(root, "serviceAccountKey.example.json");
const target = path.join(root, "serviceAccountKey.json");

if (!fs.existsSync(example)) {
  console.warn("[prebuild] serviceAccountKey.example.json not found — skip copy.");
  process.exit(0);
}
if (!fs.existsSync(target)) {
  fs.copyFileSync(example, target);
  console.log(
    "[prebuild] Created serviceAccountKey.json from template — replace with your real Firebase service account JSON."
  );
}
