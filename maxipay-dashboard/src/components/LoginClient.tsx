"use client";

import { useState } from "react";
import { signInWithEmailAndPassword } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";
import { useRouter } from "next/navigation";
import { LogIn, Eye, EyeOff, X } from "lucide-react";

export default function LoginClient() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  const [accessOpen, setAccessOpen] = useState(false);
  const [accessEmail, setAccessEmail] = useState("");
  const [accessLoading, setAccessLoading] = useState(false);
  const [accessMessage, setAccessMessage] = useState("");
  const [accessError, setAccessError] = useState("");

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      await signInWithEmailAndPassword(auth, email, password);
      router.push("/dashboard");
    } catch {
      setError("Invalid email or password. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  const openAccessModal = () => {
    setAccessEmail("");
    setAccessMessage("");
    setAccessError("");
    setAccessOpen(true);
  };

  const closeAccessModal = () => {
    setAccessOpen(false);
    setAccessLoading(false);
    setAccessMessage("");
    setAccessError("");
  };

  const submitAccessEmail = async (e: React.FormEvent) => {
    e.preventDefault();
    setAccessMessage("");
    setAccessError("");
    const trimmed = accessEmail.trim();
    if (!trimmed) {
      setAccessError("Enter your email address.");
      return;
    }
    setAccessLoading(true);
    try {
      const res = await fetch("/api/auth/employee-access", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: trimmed }),
      });
      const data = (await res.json()) as {
        ok?: boolean;
        message?: string;
        error?: string;
      };
      if (res.status === 429) {
        setAccessError(data.message ?? "Too many attempts. Try again later.");
        return;
      }
      if (!res.ok) {
        setAccessError(data.message ?? "Something went wrong. Please try again.");
        return;
      }
      if (data.message) {
        setAccessMessage(data.message);
      } else {
        setAccessMessage(
          "If that email is linked to an employee account, you will receive a message with instructions to set your password."
        );
      }
    } catch {
      setAccessError("Network error. Check your connection and try again.");
    } finally {
      setAccessLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-blue-950 to-slate-900 flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-blue-600 mb-4">
            <span className="text-white text-2xl font-bold">M</span>
          </div>
          <h1 className="text-3xl font-bold text-white">MaxiPay</h1>
          <p className="text-slate-400 mt-2">Restaurant Dashboard</p>
        </div>

        <form
          onSubmit={handleLogin}
          className="bg-white rounded-2xl shadow-2xl p-8 space-y-6"
        >
          <div>
            <h2 className="text-xl font-semibold text-slate-800">
              Welcome back
            </h2>
            <p className="text-slate-500 text-sm mt-1">
              Sign in to your dashboard
            </p>
          </div>

          {error && (
            <div className="bg-red-50 text-red-600 text-sm px-4 py-3 rounded-lg border border-red-200">
              {error}
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
                placeholder="you@restaurant.com"
                required
                className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none transition-all text-slate-800 placeholder:text-slate-400"
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
                  className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none transition-all text-slate-800 placeholder:text-slate-400 pr-12"
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

          <div className="text-center">
            <button
              type="button"
              onClick={openAccessModal}
              className="text-sm font-medium text-blue-600 hover:text-blue-700 hover:underline"
            >
              Access your account
            </button>
            <p className="text-xs text-slate-400 mt-1.5 px-2">
              For staff: use the email on your employee profile to receive a link
              and set your dashboard password.
            </p>
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white font-medium py-3 rounded-xl transition-colors flex items-center justify-center gap-2"
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

      {accessOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={(e) => {
            if (e.target === e.currentTarget) closeAccessModal();
          }}
        >
          <div
            className="bg-white rounded-2xl shadow-2xl w-full max-w-md overflow-hidden"
            role="dialog"
            aria-modal="true"
            aria-labelledby="access-account-title"
          >
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
              <h2
                id="access-account-title"
                className="text-lg font-semibold text-slate-800"
              >
                Access your account
              </h2>
              <button
                type="button"
                onClick={closeAccessModal}
                className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors"
                aria-label="Close"
              >
                <X size={18} />
              </button>
            </div>
            <form onSubmit={submitAccessEmail} className="px-6 py-5 space-y-4">
              <p className="text-sm text-slate-600">
                Enter the email saved on your employee profile. We will send
                instructions to set your dashboard password if that email is on
                file.
              </p>
              {accessMessage && (
                <div className="bg-emerald-50 text-emerald-800 text-sm px-4 py-3 rounded-lg border border-emerald-200">
                  {accessMessage}
                </div>
              )}
              {accessError && (
                <div className="bg-red-50 text-red-600 text-sm px-4 py-3 rounded-lg border border-red-200">
                  {accessError}
                </div>
              )}
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Email
                </label>
                <input
                  type="email"
                  value={accessEmail}
                  onChange={(e) => {
                    setAccessEmail(e.target.value);
                    if (accessError) setAccessError("");
                    if (accessMessage) setAccessMessage("");
                  }}
                  placeholder="you@restaurant.com"
                  autoComplete="email"
                  className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400"
                />
              </div>
              <div className="flex items-center justify-end gap-3 pt-1">
                <button
                  type="button"
                  onClick={closeAccessModal}
                  className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 hover:bg-slate-100 transition-colors"
                >
                  {accessMessage ? "Close" : "Cancel"}
                </button>
                {!accessMessage && (
                  <button
                    type="submit"
                    disabled={accessLoading}
                    className="px-5 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:bg-blue-300 transition-colors flex items-center gap-2"
                  >
                    {accessLoading ? (
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    ) : (
                      "Send link"
                    )}
                  </button>
                )}
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
