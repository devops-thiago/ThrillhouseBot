"use client";

import { useAuth } from "@/hooks/useAuth";
import GithubCorner from "@/components/GithubCorner";
import SiteFooter from "@/components/SiteFooter";

export default function LoginPage() {
  const { user, loading, accessDenied, login, logout } = useAuth();

  if (loading)
    return (
      <div style={styles.center}>
        <p>Loading...</p>
      </div>
    );
  if (accessDenied) {
    return (
      <div style={styles.page}>
        <GithubCorner />
        <div style={styles.center}>
          <div style={styles.card}>
            <h1 style={styles.title}>Access denied</h1>
            <p style={styles.subtitle}>
              Your GitHub account is signed in but does not have access to this
              dashboard.
            </p>
            <button type="button" onClick={logout} style={styles.logoutBtn}>
              Log out
            </button>
          </div>
        </div>
        <SiteFooter />
      </div>
    );
  }
  if (user) {
    window.location.href = "/dashboard/overview/";
    return null;
  }

  return (
    <div style={styles.page}>
      <GithubCorner />
      <div style={styles.center}>
        <div style={styles.card}>
          <img src="/dashboard/icon.png" alt="" style={styles.logo} />
          <h1 style={styles.title}>ThrillhouseBot</h1>
          <p style={styles.subtitle}>PR Review Dashboard</p>
          <button onClick={login} style={styles.button}>
            Sign in with GitHub
          </button>
        </div>
      </div>
      <SiteFooter />
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  page: {
    display: "flex",
    flexDirection: "column",
    minHeight: "100vh",
  },
  center: {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    flex: 1,
    minHeight: "80vh",
  },
  card: {
    textAlign: "center",
    padding: "3rem",
    background: "var(--bg-secondary)",
    borderRadius: 8,
    border: "1px solid var(--border)",
  },
  title: { fontSize: "2rem", marginBottom: "0.5rem" },
  logo: { width: 64, height: 64, marginBottom: "1rem" },
  subtitle: { color: "var(--text-muted)", marginBottom: "2rem" },
  button: {
    padding: "0.75rem 2rem",
    fontSize: "1rem",
    background: "var(--accent)",
    color: "#fff",
    border: "none",
    borderRadius: 6,
  },
  logoutBtn: {
    padding: "0.35rem 0.75rem",
    fontSize: "0.85rem",
    background: "transparent",
    color: "var(--text-muted)",
    border: "1px solid var(--border)",
    borderRadius: 6,
    cursor: "pointer",
  },
};
