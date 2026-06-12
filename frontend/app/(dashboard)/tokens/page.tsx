"use client";

import { useState } from "react";
import { Bar } from "react-chartjs-2";
import { DataLoadState } from "@/components/DataLoadState";
import { useDashboardFetch } from "@/hooks/useDashboardFetch";
import { api, TokenData } from "@/lib/api";
import type { TooltipItem } from "chart.js";
import {
  THEME,
  chartAxisColor,
  chartGridColor,
  chartTooltipStyle,
} from "@/lib/charts";

export default function TokensPage() {
  const [period, setPeriod] = useState("month");
  const { data, loading, error, retry } = useDashboardFetch(
    () => api().tokens(period),
    [period],
  );

  return (
    <DataLoadState loading={loading} error={error} onRetry={retry}>
      {data && <TokensContent data={data} period={period} setPeriod={setPeriod} />}
    </DataLoadState>
  );
}

function TokensContent({
  data,
  period,
  setPeriod,
}: {
  data: TokenData;
  period: string;
  setPeriod: (period: string) => void;
}) {
  const chartData = {
    labels: data.byModel.map((m) => m.model),
    datasets: [
      {
        label: "Input Tokens",
        data: data.byModel.map((m) => m.inputTokens),
        backgroundColor: THEME.accent,
        borderRadius: 4,
      },
      {
        label: "Output Tokens",
        data: data.byModel.map((m) => m.outputTokens),
        backgroundColor: THEME.green,
        borderRadius: 4,
      },
    ],
  };

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        labels: { color: chartAxisColor },
      },
      tooltip: {
        ...chartTooltipStyle,
        callbacks: {
          label: (ctx: TooltipItem<"bar">) =>
            `${ctx.dataset.label}: ${Number(ctx.parsed.y ?? 0).toLocaleString()}`,
        },
      },
    },
    scales: {
      x: {
        ticks: { color: chartAxisColor },
        grid: { color: chartGridColor },
      },
      y: {
        ticks: {
          color: chartAxisColor,
          callback: (value: string | number) => Number(value).toLocaleString(),
        },
        grid: { color: chartGridColor },
      },
    },
  };

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

      <div style={styles.chartCard}>
        <h3 style={{ marginBottom: "1rem" }}>
          Tokens by Model (Input vs Output)
        </h3>
        <div style={styles.chartFrame}>
          <Bar data={chartData} options={chartOptions} />
        </div>
      </div>

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
  chartFrame: {
    height: 300,
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
