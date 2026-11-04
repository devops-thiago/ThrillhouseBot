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
 * A socket ticket, or why there is none. `retry` is true when the failure is temporary: a 503,
 * which the server sends when it could not check access with GitHub, or a request that never got
 * an answer. Anything else (the session is gone, access is denied, the answer is malformed) stops
 * reconnecting.
 */
export type SocketTicket = { ticket: string } | { ticket: null; retry: boolean };

/**
 * Fetches the single-use ticket the socket presents in its query string. A WebSocket cannot carry
 * the session in a header the server can read per connection, so each connect asks for a fresh
 * ticket.
 */
export async function fetchSocketTicket(): Promise<SocketTicket> {
  let res: Response;
  try {
    res = await fetch("/api/auth/socket-ticket", {
      method: "POST",
      credentials: "include",
    });
  } catch {
    return { ticket: null, retry: true };
  }
  if (!res.ok) {
    return { ticket: null, retry: res.status === 503 };
  }
  // An answer that is not JSON is malformed, not a failed request: it stops, like any other.
  const body: { ticket?: unknown } | null = await res.json().catch(() => null);
  return typeof body?.ticket === "string" && body.ticket !== ""
    ? { ticket: body.ticket }
    : { ticket: null, retry: false };
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
    // Tries again after the current delay, doubling it up to 30 seconds.
    const scheduleReconnect = () => {
      const delay = reconnectDelay.current;
      reconnectTimeout.current = setTimeout(connect, delay);
      reconnectDelay.current = Math.min(delay * 2, 30000);
    };

    const existing = wsRef.current;
    if (
      existing &&
      (existing.readyState === WebSocket.OPEN ||
        existing.readyState === WebSocket.CONNECTING)
    ) {
      return;
    }

    existing?.close();

    const answer = await fetchSocketTicket();
    if (!mountedRef.current) {
      return;
    }
    if (answer.ticket === null) {
      setConnected(false);
      if (answer.retry) {
        scheduleReconnect();
      }
      return;
    }

    const ws = new WebSocket(socketUrl(location, answer.ticket));

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
      scheduleReconnect();
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
