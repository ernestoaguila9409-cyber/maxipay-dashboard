"use client";

import { useState } from "react";
import { signInWithEmailAndPassword } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";
import { useRouter } from "next/navigation";
import { LogIn, Eye, EyeOff, ShieldCheck } from "lucide-react";

export default function AdminLoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  const [setupMsg, setSetupMsg] = useState("");

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setSetupMsg("");
    setLoading(true);

    try {
      const cred = await signInWithEmailAndPassword(auth, email, password);
      const tokenResult = await cred.user.getIdTokenResult();

      if (tokenResult.claims.role === "super_admin") {
        router.push("/");
        return;
      }

      const token = await cred.user.getIdToken();
      const setupRes = await fetch("/api/setup", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      });
      const setupData = await setupRes.json();

      if (setupRes.ok && setupData.ok) {
        setSetupMsg("You have been promoted to admin. Signing you in...");
        await auth.signOut();
        await new Promise((r) => setTimeout(r, 1000));
        const cred2 = await signInWithEmailAndPassword(auth, email, password);
        const tokenResult2 = await cred2.user.getIdTokenResult();
        if (tokenResult2.claims.role === "super_admin") {
          router.push("/");
          return;
        }
        setSetupMsg("Promoted, but token not yet refreshed. Please sign in again.");
        await auth.signOut();
      } else {
        setError(setupData.message || "Access denied. This portal is for administrators only.");
        await auth.signOut();
      }
    } catch {
      setError("Invalid email or password.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-slate-700 mb-4">
            <ShieldCheck size={32} className="text-white" />
          </div>
          <h1 className="text-3xl font-bold text-white">MaxiPay Admin</h1>
          <p className="text-slate-400 mt-2">Administrator Portal</p>
        </div>

        <form
          onSubmit={handleLogin}
          className="bg-white rounded-2xl shadow-2xl p-8 space-y-6"
        >
          <div>
            <h2 className="text-xl font-semibold text-slate-800">Sign In</h2>
            <p className="text-slate-500 text-sm mt-1">
              Admin access only
            </p>
          </div>

          {error && (
            <div className="bg-red-50 text-red-600 text-sm px-4 py-3 rounded-lg border border-red-200">
              {error}
            </div>
          )}

          {setupMsg && (
            <div className="bg-green-50 text-green-700 text-sm px-4 py-3 rounded-lg border border-green-200">
              {setupMsg}
            </div>
          )}

          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">
                Email
              </label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="admin@maxipaypos.com"
                required
                className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:border-slate-500 focus:ring-2 focus:ring-slate-500/20 outline-none transition-all text-slate-800 placeholder:text-slate-400"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">
                Password
              </label>
              <div className="relative">
                <input
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password"
                  required
                  className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:border-slate-500 focus:ring-2 focus:ring-slate-500/20 outline-none transition-all text-slate-800 placeholder:text-slate-400 pr-12"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 transition-colors"
                >
                  {showPassword ? <EyeOff size={20} /> : <Eye size={20} />}
                </button>
              </div>
            </div>
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-slate-800 hover:bg-slate-900 disabled:bg-slate-400 text-white font-medium py-3 rounded-xl transition-colors flex items-center justify-center gap-2"
          >
            {loading ? (
              <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
            ) : (
              <>
                <LogIn size={20} />
                Sign In
              </>
            )}
          </button>
        </form>
      </div>
    </div>
  );
}
