import type { UserRecord } from "firebase-admin/auth";

export type PasswordStatus = "set" | "not_set" | "sso_only" | "no_account";

/** Human label for admin UI */
export function passwordStatusLabel(s: PasswordStatus): string {
  switch (s) {
    case "set":
      return "Password set";
    case "not_set":
      return "Password not set";
    case "sso_only":
      return "SSO only (no email/password)";
    case "no_account":
      return "No Firebase account";
    default:
      return s;
  }
}

/**
 * Firebase never exposes plaintext passwords. For email/password users, "set" means
 * they have signed in at least once after account creation (likely completed setup).
 */
export function passwordStatusFromUserRecord(user: UserRecord | null): PasswordStatus {
  if (!user) return "no_account";
  const hasPassword = user.providerData?.some((p) => p.providerId === "password");
  if (!hasPassword) return "sso_only";
  const c = user.metadata.creationTime;
  const l = user.metadata.lastSignInTime;
  if (!c || !l) return "not_set";
  const ct = new Date(c).getTime();
  const lt = new Date(l).getTime();
  if (!Number.isFinite(ct) || !Number.isFinite(lt)) return "not_set";
  return lt > ct + 1000 ? "set" : "not_set";
}
