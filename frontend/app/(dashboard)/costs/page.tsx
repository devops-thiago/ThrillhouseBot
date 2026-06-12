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
  PieChart,
  Pie,
  Cell,
  Legend,
} from "recharts";
import { api, CostData } from "@/lib/api";

const COLORS = [
  "#58a6ff",
  "#3fb950",
  "#d2991d",
  "#f85149",
  "#bc8cff",
  "#ff9bce",
];

export default function CostsPage() {
  const [data, setData] = useState<CostData | null>(null);
  const [period, setPeriod] = useState("month");

  useEffect(() => {
    api().costs(period).then(setData).catch(console.error);
  }, [period]);

  if (!data) return <p>Loading...</p>;

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

      {/* Bar chart — cost by model */}
      <ChartCard title="Cost by Model">
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={data.byModel}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis
              dataKey="model"
              stroke="var(--text-muted)"
              tick={{ fontSize: 12 }}
            />
            <YAxis
              stroke="var(--text-muted)"
              tick={{ fontSize: 12 }}
              tickFormatter={(v) => `$${v}`}
            />
            <Tooltip
              contentStyle={{
                background: "var(--bg-secondary)",
                border: "1px solid var(--border)",
                borderRadius: 6,
              }}
              formatter={(value) => [`$${Number(value).toFixed(6)}`, "Cost"]}
            />
            <Bar dataKey="cost" fill="var(--accent)" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </ChartCard>

      {/* Pie chart — model distribution */}
      <ChartCard title="Model Distribution">
        <ResponsiveContainer width="100%" height={300}>
          <PieChart>
            <Pie
              data={data.byModel}
              dataKey="cost"
              nameKey="model"
              cx="50%"
              cy="50%"
              outerRadius={100}
              label={({ name, value }) =>
                `${name} ($${Number(value).toFixed(4)})`
              }
            >
              {data.byModel.map((_, i) => (
                <Cell key={i} fill={COLORS[i % COLORS.length]} />
              ))}
            </Pie>
            <Tooltip
              contentStyle={{
                background: "var(--bg-secondary)",
                border: "1px solid var(--border)",
                borderRadius: 6,
              }}
            />
            <Legend />
          </PieChart>
        </ResponsiveContainer>
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
  select: {
    padding: "0.4rem 0.8rem",
    background: "var(--bg-secondary)",
    color: "var(--text)",
    border: "1px solid var(--border)",
    borderRadius: 6,
    fontSize: "0.9rem",
  },
};
