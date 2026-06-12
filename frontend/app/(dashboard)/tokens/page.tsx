"use client";

import { useEffect, useState } from "react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts";
import { api, TokenData } from "@/lib/api";

export default function TokensPage() {
  const [data, setData] = useState<TokenData | null>(null);
  const [period, setPeriod] = useState("month");

  useEffect(() => {
    api().tokens(period).then(setData).catch(console.error);
  }, [period]);

  if (!data) return <p>Loading...</p>;

  const chartData = data.byModel.map((m) => ({
    model: m.model,
    input: m.inputTokens,
    output: m.outputTokens,
  }));

  return (
    <div>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: "1rem",
          marginBottom: "1.5rem",
        }}
      >
        <h1>Token Analytics</h1>
        <select
          value={period}
          onChange={(e) => setPeriod(e.target.value)}
          style={styles.select}
        >
          <option value="day">Last 24h</option>
          <option value="week">Last 7 days</option>
          <option value="month">Last 30 days</option>
        </select>
      </div>

      <div style={styles.grid}>
        <div style={styles.chartCard}>
          <div style={styles.cardLabel}>Total Tokens ({period})</div>
          <div style={{ fontSize: "2rem", fontWeight: 700 }}>
            {data.totalTokens.toLocaleString()}
          </div>
        </div>
      </div>

      {/* Stacked bar — input vs output by model */}
      <div style={styles.chartCard}>
        <h3 style={{ marginBottom: "1rem" }}>
          Tokens by Model (Input vs Output)
        </h3>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis
              dataKey="model"
              stroke="var(--text-muted)"
              tick={{ fontSize: 12 }}
            />
            <YAxis
              stroke="var(--text-muted)"
              tick={{ fontSize: 12 }}
              tickFormatter={(v) => v.toLocaleString()}
            />
            <Tooltip
              contentStyle={{
                background: "var(--bg-secondary)",
                border: "1px solid var(--border)",
                borderRadius: 6,
              }}
              formatter={(v) => Number(v).toLocaleString()}
            />
            <Legend />
            <Bar
              dataKey="input"
              name="Input Tokens"
              fill="var(--accent)"
              radius={[4, 4, 0, 0]}
            />
            <Bar
              dataKey="output"
              name="Output Tokens"
              fill="var(--green)"
              radius={[4, 4, 0, 0]}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Model table */}
      <div style={styles.chartCard}>
        <h3 style={{ marginBottom: "1rem" }}>Model Breakdown</h3>
        <table style={styles.table}>
          <thead>
            <tr>
              <th style={styles.thLeft}>Model</th>
              <th style={styles.th}>Reviews</th>
              <th style={styles.th}>Input Tokens</th>
              <th style={styles.th}>Output Tokens</th>
              <th style={styles.th}>Total</th>
            </tr>
          </thead>
          <tbody>
            {data.byModel.map((m) => (
              <tr key={m.model}>
                <td style={styles.tdLeft}>{m.model}</td>
                <td style={styles.td}>{m.count}</td>
                <td style={styles.td}>{m.inputTokens.toLocaleString()}</td>
                <td style={styles.td}>{m.outputTokens.toLocaleString()}</td>
                <td style={styles.td}>{m.totalTokens.toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  grid: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit, minmax(250px, 1fr))",
    gap: "1rem",
    marginBottom: "1.5rem",
  },
  chartCard: {
    background: "var(--bg-secondary)",
    border: "1px solid var(--border)",
    borderRadius: 8,
    padding: "1.5rem",
    marginBottom: "1rem",
  },
  cardLabel: {
    fontSize: "0.8rem",
    color: "var(--text-muted)",
    marginBottom: "0.5rem",
    textTransform: "uppercase",
  },
  select: {
    padding: "0.4rem 0.8rem",
    background: "var(--bg-secondary)",
    color: "var(--text)",
    border: "1px solid var(--border)",
    borderRadius: 6,
    fontSize: "0.9rem",
  },
  table: { width: "100%", borderCollapse: "collapse" },
  th: {
    textAlign: "right",
    padding: "0.6rem 0.75rem",
    borderBottom: "1px solid var(--border)",
    fontSize: "0.75rem",
    fontWeight: 600,
    color: "var(--text-muted)",
    textTransform: "uppercase",
    whiteSpace: "nowrap",
  },
  thLeft: {
    textAlign: "left",
    padding: "0.6rem 0.75rem",
    borderBottom: "1px solid var(--border)",
    fontSize: "0.75rem",
    fontWeight: 600,
    color: "var(--text-muted)",
    textTransform: "uppercase",
    whiteSpace: "nowrap",
  },
  td: {
    textAlign: "right",
    padding: "0.6rem 0.75rem",
    borderBottom: "1px solid var(--border)",
    whiteSpace: "nowrap",
    fontVariantNumeric: "tabular-nums",
  },
  tdLeft: {
    textAlign: "left",
    padding: "0.6rem 0.75rem",
    borderBottom: "1px solid var(--border)",
  },
};
