"use client";

import { useState } from "react";
import { Bar, Pie } from "react-chartjs-2";
import { DataLoadState } from "@/components/DataLoadState";
import { useDashboardFetch } from "@/hooks/useDashboardFetch";
import { api, CostData } from "@/lib/api";
import type { TooltipItem } from "chart.js";
import {
  CHART_COLORS,
  THEME,
  chartAxisColor,
  chartGridColor,
  chartTooltipStyle,
} from "@/lib/charts";

export default function CostsPage() {
  const [period, setPeriod] = useState("month");
  const { data, loading, error, retry } = useDashboardFetch(
    () => api().costs(period),
    [period],
  );

  return (
    <DataLoadState loading={loading} error={error} onRetry={retry}>
      {data && <CostsContent data={data} period={period} setPeriod={setPeriod} />}
    </DataLoadState>
  );
}

function CostsContent({
  data,
  period,
  setPeriod,
}: {
  data: CostData;
  period: string;
  setPeriod: (period: string) => void;
}) {
  const barData = {
    labels: data.byModel.map((m) => m.model),
    datasets: [
      {
        label: "Cost",
        data: data.byModel.map((m) => m.cost),
        backgroundColor: THEME.accent,
        borderRadius: 4,
      },
    ],
  };

  const pieData = {
    labels: data.byModel.map((m) => m.model),
    datasets: [
      {
        data: data.byModel.map((m) => m.cost),
        backgroundColor: data.byModel.map(
          (_, i) => CHART_COLORS[i % CHART_COLORS.length],
        ),
      },
    ],
  };

  const axisOptions = {
    ticks: { color: chartAxisColor },
    grid: { color: chartGridColor },
  };

  const barOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
      tooltip: {
        ...chartTooltipStyle,
        callbacks: {
          label: (ctx: TooltipItem<"bar">) =>
            `Cost: $${Number(ctx.parsed.y ?? 0).toFixed(6)}`,
        },
      },
    },
    scales: {
      x: axisOptions,
      y: {
        ...axisOptions,
        ticks: {
          color: chartAxisColor,
          callback: (value: string | number) => `$${value}`,
        },
      },
    },
  };

  const pieOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        labels: { color: chartAxisColor },
      },
      tooltip: chartTooltipStyle,
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
        <h1>Cost Analytics</h1>
        <select
          value={period}
          onChange={(e) => setPeriod(e.target.value)}
          style={styles.select}
        >
          <option value="day">Last 24h</option>
          <option value="week">Last 7 days</option>
          <option value="month">Last 30 days</option>
          <option value="year">Last 365 days</option>
        </select>
      </div>

      <div style={styles.grid}>
        <ChartCard title={`Total Cost (${period})`}>
          <div
            style={{
              fontSize: "2rem",
              fontWeight: 700,
              color: "var(--yellow)",
              marginBottom: "1rem",
            }}
          >
            ${data.totalCost.toFixed(6)}
          </div>
        </ChartCard>
      </div>

      <ChartCard title="Cost by Model">
        <div style={styles.chartFrame}>
          <Bar data={barData} options={barOptions} />
        </div>
      </ChartCard>

      <ChartCard title="Model Distribution">
        <div style={styles.chartFrame}>
          <Pie data={pieData} options={pieOptions} />
        </div>
      </ChartCard>
    </div>
  );
}

function ChartCard({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div style={styles.chartCard}>
      <h3 style={{ marginBottom: "1rem" }}>{title}</h3>
      {children}
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
  select: {
    padding: "0.4rem 0.8rem",
    background: "var(--bg-secondary)",
    color: "var(--text)",
    border: "1px solid var(--border)",
    borderRadius: 6,
    fontSize: "0.9rem",
  },
};
