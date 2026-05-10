"use client";

import { useEffect, useState, useCallback } from "react";
import {
  verifyPasswordResetCode,
  confirmPasswordReset,
} from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";
import { useRouter, useSearchParams } from "next/navigation";
import { Eye, EyeOff, Loader2 } from "lucide-react";

function safeContinueUrl(raw: string | null): string | null {
  if (!raw || typeof window === "undefined") return null;
  try {
    const decoded = decodeURIComponent(raw);
    const u = new URL(decoded);
    if (u.origin === window.location.origin) return u.toString();
  } catch {
    /* ignore */
  }
  return null;
}

export default function ResetPasswordClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const mode = searchParams.get("mode");
  const oobCode = searchParams.get("oobCode");

  const [email, setEmail] = useState<string | null>(null);
  const [initError, setInitError] = useState("");
  const [checking, setChecking] = useState(true);

  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [submitError, setSubmitError] = useState("");
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (mode !== "resetPassword" || !oobCode) {
      setInitError(
        "This link is invalid or incomplete. Request a new password reset from the login page."
      );
      setChecking(false);
      return;
    }

    let cancelled = false;
    (async () => {
      try {
        const addr = await verifyPasswordResetCode(auth, oobCode);
        if (!cancelled) setEmail(addr);
      } catch {
        if (!cancelled) {
          setInitError(
            "This reset link has expired or was already used. Request a new one from the login page."
          );
        }
      } finally {
        if (!cancelled) setChecking(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [mode, oobCode]);

  const onSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setSubmitError("");
      if (!oobCode) return;

      if (password.length < 6) {
        setSubmitError("Password must be at least 6 characters.");
        return;
      }
      if (password !== confirmPassword) {
        setSubmitError("Passwords do not match. Type the same password in both fields.");
        return;
      }

      setLoading(true);
      try {
        await confirmPasswordReset(auth, oobCode, password);
        setDone(true);
        const next = safeContinueUrl(searchParams.get("continueUrl"));
        window.setTimeout(() => {
          if (next) window.location.assign(next);
          else router.push("/login?reset=success");
        }, 1200);
      } catch {
        setSubmitError(
          "Could not update your password. The link may have expired — request a new reset email."
        );
      } finally {
        setLoading(false);
      }
    },
    [oobCode, password, confirmPassword, router, searchParams]
  );

  if (checking) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-blue-950 to-slate-900 px-4">
        <Loader2 className="h-14 w-14 text-blue-400 animate-spin" aria-hidden />
        <p className="mt-6 text-lg text-slate-300">Checking your reset link…</p>
      </div>
    );
  }

  if (initError) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-blue-950 to-slate-900 px-4">
        <div className="w-full max-w-2xl rounded-3xl bg-white/95 p-10 sm:p-14 shadow-2xl text-center">
          <div className="inline-flex h-20 w-20 items-center justify-center rounded-2xl bg-blue-600 mb-6">
            <span className="text-white text-3xl font-bold">M</span>
          </div>
          <h1 className="text-3xl sm:text-4xl font-bold text-slate-900">MaxiPay</h1>
          <p className="mt-6 text-lg text-slate-600 leading-relaxed">{initError}</p>
          <button
            type="button"
            onClick={() => router.push("/login")}
            className="mt-10 w-full sm:w-auto min-w-[200px] rounded-xl bg-blue-600 px-8 py-4 text-lg font-semibold text-white hover:bg-blue-700 transition-colors"
          >
            Back to login
          </button>
          <p className="mt-12 text-xs tracking-widest text-slate-500 uppercase">
            Powered by VOLT MERCHANT SOLUTIONS
          </p>
        </div>
      </div>
    );
  }

  if (done) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-blue-950 to-slate-900 px-4">
        <div className="w-full max-w-2xl rounded-3xl bg-white/95 p-10 sm:p-14 shadow-2xl text-center">
          <div className="inline-flex h-20 w-20 items-center justify-center rounded-2xl bg-emerald-600 mb-6">
            <span className="text-white text-3xl font-bold">✓</span>
          </div>
          <h1 className="text-3xl sm:text-4xl font-bold text-slate-900">Password updated</h1>
          <p className="mt-4 text-lg text-slate-600">Taking you to sign in…</p>
          <p className="mt-12 text-xs tracking-widest text-slate-500 uppercase">
            Powered by VOLT MERCHANT SOLUTIONS
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-blue-950 to-slate-900 px-4 py-10">
      <div className="w-full max-w-2xl rounded-3xl bg-white/95 p-8 sm:p-12 md:p-16 shadow-2xl border border-white/20">
        <div className="text-center mb-10 md:mb-12">
          <div className="inline-flex h-20 w-20 md:h-24 md:w-24 items-center justify-center rounded-2xl bg-blue-600 mb-5 shadow-lg shadow-blue-600/30">
            <span className="text-white text-3xl md:text-4xl font-bold">M</span>
          </div>
          <h1 className="text-4xl md:text-5xl font-bold text-slate-900 tracking-tight">MaxiPay</h1>
          <p className="mt-2 text-lg md:text-xl text-slate-500">Restaurant Dashboard</p>
          <p className="mt-8 text-xl md:text-2xl font-semibold text-slate-800">
            Create your password
          </p>
          <p className="mt-2 text-base md:text-lg text-slate-600 break-all">
            for <span className="font-medium text-slate-800">{email}</span>
          </p>
        </div>

        <form onSubmit={onSubmit} className="space-y-8">
          {submitError && (
            <div
              className="rounded-xl bg-red-50 border border-red-200 px-5 py-4 text-red-800 text-base md:text-lg"
              role="alert"
            >
              {submitError}
            </div>
          )}

          <div>
            <label
              htmlFor="reset-password"
              className="block text-base md:text-lg font-medium text-slate-700 mb-3"
            >
              New password
            </label>
            <div className="relative">
              <input
                id="reset-password"
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-xl border-2 border-slate-200 bg-white px-5 py-4 md:py-5 text-lg md:text-xl text-slate-900 outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-500/15"
                placeholder="Enter a strong password"
                minLength={6}
                required
              />
              <button
                type="button"
                tabIndex={-1}
                className="absolute right-4 top-1/2 -translate-y-1/2 p-2 text-slate-500 hover:text-slate-800"
                onClick={() => setShowPassword((v) => !v)}
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? <EyeOff className="h-6 w-6" /> : <Eye className="h-6 w-6" />}
              </button>
            </div>
          </div>

          <div>
            <label
              htmlFor="reset-confirm-password"
              className="block text-base md:text-lg font-medium text-slate-700 mb-3"
            >
              Confirm password
            </label>
            <div className="relative">
              <input
                id="reset-confirm-password"
                type={showConfirm ? "text" : "password"}
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="w-full rounded-xl border-2 border-slate-200 bg-white px-5 py-4 md:py-5 text-lg md:text-xl text-slate-900 outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-500/15"
                placeholder="Re-enter your password"
                minLength={6}
                required
              />
              <button
                type="button"
                tabIndex={-1}
                className="absolute right-4 top-1/2 -translate-y-1/2 p-2 text-slate-500 hover:text-slate-800"
                onClick={() => setShowConfirm((v) => !v)}
                aria-label={showConfirm ? "Hide confirm password" : "Show confirm password"}
              >
                {showConfirm ? <EyeOff className="h-6 w-6" /> : <Eye className="h-6 w-6" />}
              </button>
            </div>
            {confirmPassword.length > 0 && password !== confirmPassword && (
              <p className="mt-2 text-sm md:text-base text-amber-700">
                Passwords must match before you can save.
              </p>
            )}
          </div>

          <button
            type="submit"
            disabled={loading || password.length < 6 || password !== confirmPassword}
            className="w-full rounded-xl bg-blue-600 py-4 md:py-5 text-lg md:text-xl font-semibold text-white shadow-lg shadow-blue-600/25 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {loading ? (
              <span className="inline-flex items-center justify-center gap-2">
                <Loader2 className="h-6 w-6 animate-spin" />
                Saving…
              </span>
            ) : (
              "Save password"
            )}
          </button>
        </form>

        <p className="mt-12 text-center text-xs md:text-sm tracking-[0.2em] text-slate-500 uppercase">
          Powered by VOLT MERCHANT SOLUTIONS
        </p>
      </div>
    </div>
  );
}
