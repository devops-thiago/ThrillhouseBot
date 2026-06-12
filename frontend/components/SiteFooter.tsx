export default function SiteFooter() {
  return (
    <footer style={styles.footer}>
      © 2026 Thiago Gonzaga · Licensed under the{" "}
      <a
        href="https://github.com/devops-thiago/ThrillhouseBot/blob/main/LICENSE"
        target="_blank"
        rel="noopener noreferrer"
        style={styles.link}
      >
        Apache License 2.0
      </a>
    </footer>
  );
}

const styles: Record<string, React.CSSProperties> = {
  footer: {
    padding: "1.5rem",
    textAlign: "center",
    fontSize: "0.8rem",
    color: "var(--text-muted)",
    borderTop: "1px solid var(--border)",
  },
  link: {
    color: "var(--text-muted)",
    textDecoration: "underline",
  },
};
