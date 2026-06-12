"use client";

import Link from "next/link";
import { useEffect } from "react";
import { usePathname } from "next/navigation";
import { useAuth } from "@/hooks/useAuth";
import SiteFooter from "@/components/SiteFooter";

// Paths are relative to basePath (/dashboard) — do not include /dashboard here
const NAV = [
  { href: "/overview", label: "Overview" },
  { href: "/costs", label: "Costs" },
  { href: "/tokens", label: "Tokens" },
  { href: "/sessions", label: "Sessions" },
];

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  const { user, loading, accessDenied, logout } = useAuth();

  useEffect(() => {
    if (!loading && !user && !accessDenied) {
      window.location.replace("/dashboard/");
    }
  }, [loading, user, accessDenied]);

  if (!loading && accessDenied) {
    return (
      <div style={styles.deniedPage}>
        <div style={styles.deniedCard}>
          <h1 style={{ marginTop: 0 }}>Access denied</h1>
          <p style={{ color: "var(--text-muted)" }}>
            Your GitHub account is signed in but does not have access to this dashboard.
          </p>
          <button type="button" onClick={logout} style={styles.logoutBtn}>
            Log out
          </button>
        </div>
      </div>
    );
  }

  return (
    <div
      style={{ minHeight: "100vh", display: "flex", flexDirection: "column" }}
    >
      <nav style={styles.nav}>
        <span style={styles.brand}>
          <img src="/dashboard/icon-32.png" alt="" style={styles.brandIcon} />
          ThrillhouseBot
        </span>
        <div style={styles.links}>
          {NAV.map((n) => (
            <Link
              key={n.href}
              href={n.href}
              style={{
                ...styles.link,
                ...(pathname === n.href || pathname === `${n.href}/`
                  ? styles.activeLink
                  : {}),
              }}
            >
              {n.label}
            </Link>
          ))}
        </div>
        {!loading && user && (
          <div style={styles.userArea}>
            {user.avatarUrl ? (
              <img src={user.avatarUrl} alt="" style={styles.avatar} />
            ) : null}
            <span style={styles.userName}>{user.name}</span>
            <button type="button" onClick={logout} style={styles.logoutBtn}>
              Log out
            </button>
          </div>
        )}
      </nav>
      <main
        className="container"
        style={{
          width: "100%",
          paddingTop: "5rem",
          paddingBottom: "2rem",
          flex: 1,
        }}
      >
        {children}
      </main>
      <SiteFooter />
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  nav: {
    position: "fixed",
    top: 0,
    left: 0,
    right: 0,
    zIndex: 100,
    display: "flex",
    alignItems: "center",
    gap: "1.5rem",
    padding: "0 1.5rem",
    height: 56,
    background: "var(--bg-secondary)",
    borderBottom: "1px solid var(--border)",
  },
  brand: {
    display: "flex",
    alignItems: "center",
    gap: "0.5rem",
    fontWeight: 700,
    fontSize: "1.1rem",
  },
  brandIcon: { width: 28, height: 28 },
  links: { display: "flex", gap: "0.5rem" },
  link: {
    padding: "0.4rem 0.8rem",
    borderRadius: 6,
    fontSize: "0.9rem",
    color: "var(--text-muted)",
    textDecoration: "none",
  },
  activeLink: { background: "rgba(88,166,255,0.15)", color: "var(--accent)" },
  userArea: {
    marginLeft: "auto",
    display: "flex",
    alignItems: "center",
    gap: "0.75rem",
  },
  avatar: {
    width: 28,
    height: 28,
    borderRadius: "50%",
  },
  userName: {
    fontSize: "0.85rem",
    color: "var(--text-muted)",
    maxWidth: 160,
    overflow: "hidden",
    textOverflow: "ellipsis",
    whiteSpace: "nowrap",
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
  deniedPage: {
    minHeight: "100vh",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    padding: "2rem",
  },
  deniedCard: {
    maxWidth: 420,
    padding: "2rem",
    background: "var(--bg-secondary)",
    border: "1px solid var(--border)",
    borderRadius: 8,
    textAlign: "center",
  },
};
