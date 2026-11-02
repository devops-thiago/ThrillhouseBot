"use client";

import { useEffect, useRef, useState, useCallback } from "react";
import { MOCK_MODE, mockSessionEvents } from "@/lib/mock-data";

export interface SessionEvent {
  type:
    | "keepalive"
    | "review.started"
    | "review.phase"
    | "review.stream"
    | "review.reasoning"
    | "review.stream.failed"
    | "review.batch"
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

/**
 * Fetches the single-use ticket the socket presents in its query string. A WebSocket cannot carry
 * the session in a header the server can read per connection, so each connect asks for a fresh
 * ticket; null when the session is gone or not allowed, which stops reconnecting.
 */
export async function fetchSocketTicket(): Promise<string | null> {
  try {
    const res = await fetch("/api/auth/socket-ticket", {
      method: "POST",
      credentials: "include",
    });
    if (!res.ok) {
      return null;
    }
    const body: { ticket?: unknown } = await res.json();
    return typeof body.ticket === "string" && body.ticket !== "" ? body.ticket : null;
  } catch {
    return null;
  }
}

export function socketUrl(location: Pick<Location, "protocol" | "host">, ticket: string): string {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${location.host}/ws/dashboard?ticket=${encodeURIComponent(ticket)}`;
}

/**
 * The dashboard's event feed. `events` keeps the latest 100, newest first. `onEvent`, when given,
 * sees every event as it arrives, oldest first, for a page that folds the whole stream instead of
 * reading a window that the stream and reasoning chunks soon fill.
 */
export function useWebSocket(onEvent?: (event: SessionEvent) => void) {
  const [events, setEvents] = useState<SessionEvent[]>([]);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectDelay = useRef(1000);
  const mountedRef = useRef(true);

  const connect = useCallback(async () => {
    if (!mountedRef.current) {
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

    const ticket = await fetchSocketTicket();
    if (!ticket || !mountedRef.current) {
      setConnected(false);
      return;
    }

    const ws = new WebSocket(socketUrl(location, ticket));

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
        onEventRef.current?.(event);
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
    if (MOCK_MODE) {
      const mocked = mockSessionEvents();
      [...mocked].reverse().forEach((event) => onEventRef.current?.(event));
      setEvents(mocked);
      setConnected(true);
      return;
    }
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
