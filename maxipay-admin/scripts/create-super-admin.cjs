/**
 * Create or update a Firebase Auth user and set custom claim role=super_admin.
 *
 * Run from maxipay-admin/ (uses FIREBASE_SERVICE_ACCOUNT_JSON from .env.local):
 *   node scripts/create-super-admin.cjs you@email.com "YourPassword"
 *
 * Do not commit passwords; pass them on the command line only for local setup.
 */

const fs = require("fs");
const path = require("path");
const admin = require("firebase-admin");

const envPath = path.join(__dirname, "..", ".env.local");
if (fs.existsSync(envPath)) {
  const lines = fs.readFileSync(envPath, "utf-8").split("\n");
  for (const line of lines) {
    const eq = line.indexOf("=");
    if (eq > 0) {
      const key = line.slice(0, eq).trim();
      let val = line.slice(eq + 1).trim();
      if (key && val && !process.env[key]) {
        if (
          (val.startsWith('"') && val.endsWith('"')) ||
          (val.startsWith("'") && val.endsWith("'"))
        ) {
          val = val.slice(1, -1);
        }
        process.env[key] = val;
      }
    }
  }
}

const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
if (!raw) {
  console.error("FIREBASE_SERVICE_ACCOUNT_JSON not found. Set it in maxipay-admin/.env.local");
  process.exit(1);
}

const sa = JSON.parse(raw);
if (sa.private_key) {
  sa.private_key = sa.private_key.replace(/\\n/g, "\n");
}

if (!admin.apps.length) {
  admin.initializeApp({ credential: admin.credential.cert(sa) });
}

const email = process.argv[2] || process.env.ADMIN_EMAIL;
const password = process.argv[3] || process.env.ADMIN_PASSWORD;

if (!email || !password) {
  console.error("Usage: node scripts/create-super-admin.cjs <email> <password>");
  process.exit(1);
}

async function main() {
  const auth = admin.auth();
  let user;
  try {
    user = await auth.getUserByEmail(email);
    await auth.updateUser(user.uid, { password });
    console.log("Updated existing user password:", email);
  } catch (e) {
    if (e.code === "auth/user-not-found") {
      user = await auth.createUser({
        email,
        password,
        emailVerified: false,
      });
      console.log("Created user:", email);
    } else {
      throw e;
    }
  }

  const prior = user.customClaims || {};
  await auth.setCustomUserClaims(user.uid, {
    ...prior,
    role: "super_admin",
  });

  console.log("Set custom claims: role=super_admin (uid:", user.uid + ")");
  console.log("Sign in at the admin app with this email and password. If claims were cached, sign out everywhere once.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
