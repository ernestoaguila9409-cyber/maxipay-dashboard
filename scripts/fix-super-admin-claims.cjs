/**
 * One-off script: keep super_admin only on ernestoaguila9409@gmail.com.
 * Delete any other Firebase Auth user that has role=super_admin so the
 * email can be re-used for merchant owner accounts.
 *
 * Usage:  node scripts/fix-super-admin-claims.cjs
 *
 * Requires FIREBASE_SERVICE_ACCOUNT_JSON env var (same as maxipay-admin).
 */

const admin = require("firebase-admin");
const path = require("path");

// Load .env.local from maxipay-admin so we pick up the service-account JSON
try {
  require("dotenv").config({
    path: path.resolve(__dirname, "../maxipay-admin/.env.local"),
  });
} catch {
  // dotenv may not be installed at root; that's fine if env is already set
}

const KEEP_EMAIL = "ernestoaguila9409@gmail.com";

function initAdmin() {
  if (admin.apps.length) return;
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!raw) {
    console.error("Missing FIREBASE_SERVICE_ACCOUNT_JSON env var");
    process.exit(1);
  }
  const sa = JSON.parse(raw);
  if (typeof sa.private_key === "string") {
    sa.private_key = sa.private_key.replace(/\\n/g, "\n");
  }
  admin.initializeApp({ credential: admin.credential.cert(sa) });
}

async function main() {
  initAdmin();
  const auth = admin.auth();

  let nextPageToken;
  let cleaned = 0;

  do {
    const result = await auth.listUsers(1000, nextPageToken);
    for (const user of result.users) {
      const role = user.customClaims?.role;
      if (role === "super_admin" && user.email !== KEEP_EMAIL) {
        console.log(
          `Removing super_admin claims from ${user.email || user.uid}`
        );
        await auth.setCustomUserClaims(user.uid, {});
        cleaned++;
      }
    }
    nextPageToken = result.pageToken;
  } while (nextPageToken);

  if (cleaned === 0) {
    console.log(
      `No extra super_admin accounts found. Only ${KEEP_EMAIL} has the role.`
    );
  } else {
    console.log(`Done. Cleared super_admin from ${cleaned} account(s).`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
