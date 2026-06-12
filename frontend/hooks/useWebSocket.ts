"use client";

import { useEffect, useRef, useState, useCallback } from "react";

export interface SessionEvent {
  type:
    | "keepalive"
    | "review.started"
    | "review.stream"
    | "review.stream.failed"
    | "review.retry"
    | "review.progress"
    | "review.completed"
    | "review.failed";
  sessionId: number;
  repository: string;
  prNumber: number;
  prTitle: string;
  commitSha: string;
  timestamp: string;
  data: Record<string, any>;
}

async function isAuthenticated(): Promise<boolean> {
  try {
    const res = await fetch("/api/auth/me", { credentials: "include" });
    return res.ok;
  } catch {
    return false;
  }
}

export function useWebSocket() {
  const [events, setEvents] = useState<SessionEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectDelay = useRef(1000);
  const mountedRef = useRef(true);

  const connect = useCallback(async () => {
    if (!mountedRef.current) {
      return;
    }

    if (!(await isAuthenticated())) {
      setConnected(false);
      return;
    }

    const existing = wsRef.current;
    if (
      existing &&
      (existing.readyState === WebSocket.OPEN ||
        existing.readyState === WebSocket.CONNECTING)
    ) {
      return;
    }

    existing?.close();

    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(`${protocol}//${location.host}/ws/dashboard`);

    ws.onopen = () => {
      if (!mountedRef.current) {
        ws.close();
        return;
      }
      setConnected(true);
      reconnectDelay.current = 1000;
    };

    ws.onmessage = (e) => {
      try {
        const event: SessionEvent = JSON.parse(e.data);
        if (event.type === "keepalive") {
          return;
        }
        setEvents((prev) => [event, ...prev].slice(0, 100));
      } catch {
        /* ignore malformed */
      }
    };

    ws.onclose = () => {
      if (!mountedRef.current) {
        return;
      }
      setConnected(false);
      wsRef.current = null;
      const delay = reconnectDelay.current;
      reconnectTimeout.current = setTimeout(connect, delay);
      reconnectDelay.current = Math.min(delay * 2, 30000);
    };

    ws.onerror = () => ws.close();
    wsRef.current = ws;
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    connect();
    return () => {
      mountedRef.current = false;
      wsRef.current?.close();
      wsRef.current = null;
      if (reconnectTimeout.current) {
        clearTimeout(reconnectTimeout.current);
      }
    };
  }, [connect]);

  return { events, connected };
}
