import {
  ArcElement,
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Legend,
  LinearScale,
  Tooltip,
} from "chart.js";

ChartJS.register(
  CategoryScale,
  LinearScale,
  BarElement,
  ArcElement,
  Tooltip,
  Legend,
);

// Chart.js draws on a <canvas> and cannot resolve CSS custom properties, so
// these mirror the hex values in app/globals.css.
export const THEME = {
  accent: "#58a6ff",
  green: "#3fb950",
  text: "#e6edf3",
  textMuted: "#8b949e",
  border: "#30363d",
  bgSecondary: "#161b22",
};

export const CHART_COLORS = [
  "#58a6ff",
  "#3fb950",
  "#d2991d",
  "#f85149",
  "#bc8cff",
  "#ff9bce",
];

export const chartTooltipStyle = {
  backgroundColor: THEME.bgSecondary,
  borderColor: THEME.border,
  borderWidth: 1,
  titleColor: THEME.text,
  bodyColor: THEME.text,
};

export const chartAxisColor = THEME.textMuted;
export const chartGridColor = THEME.border;
