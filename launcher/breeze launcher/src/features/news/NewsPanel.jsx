import { useCallback, useEffect, useMemo, useState } from "react";
import { getNews } from "../../services/breezeApi";

/**
 * News on the play page: the team's announcements (GET /announcements) and
 * nothing else. Nothing is built into the launcher: with no announcements the
 * panel says so, and when the API cannot be reached it says that instead, so
 * "there is no news" and "the news could not be loaded" never look alike.
 *
 * Announcements also arrive on the bell as notifications. An API older than
 * 1.0.38 has no /announcements (it answers 404); the panel then lists the
 * announcement notifications this account has, which are real ones too.
 */
const ANNOUNCEMENT_TYPE = /^Announcement from (.+?)(?: \((.+)\))?$/;
const REFRESH_MS = 5 * 60 * 1000;

function fromNotifications(notifications) {
  return (notifications || [])
    .filter((n) => n?.data?.kind === "announcement" || ANNOUNCEMENT_TYPE.test(String(n?.type || "")))
    .map((n) => {
      const m = ANNOUNCEMENT_TYPE.exec(String(n.type || "")) || [];
      return {
        id: n.data?.announcement_id || n.id,
        title: n.title,
        body: n.body || null,
        from: n.data?.from_name || m[1] || "Breeze",
        role: m[2] || "Team",
        created_at: n.created_at,
      };
    });
}

function when(iso) {
  const t = Date.parse(iso || "");
  if (!Number.isFinite(t)) return "";
  const days = Math.floor((Date.now() - t) / 86_400_000);
  if (days <= 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;
  return new Date(t).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function clock(ms) {
  return new Date(ms).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
}

function NewsItem({ title, meta, children, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <article className={`news-item ${open ? "open" : ""}`}>
      <button type="button" className="news-head" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className="news-kind">Announcement</span>
        <span className="news-when">{meta}</span>
        <span className="news-title">{title}</span>
      </button>
      <div className="news-body">{children}</div>
    </article>
  );
}

export function NewsPanel({ notifications }) {
  // status: loading, ready, older-api (no /announcements), unreachable.
  // items and loadedAt are the last list the API really sent, kept through a
  // failed refresh so the panel can say how old it is.
  const [state, setState] = useState({ status: "loading", items: [], loadedAt: null });

  const load = useCallback(() => {
    getNews()
      .then((list) => setState({ status: "ready", items: list, loadedAt: Date.now() }))
      .catch((error) => setState((prev) => (error?.status === 404
        ? { status: "older-api", items: [], loadedAt: null }
        : { ...prev, status: "unreachable" })));
  }, []);

  useEffect(() => {
    load();
    // New announcements show within a few minutes while the launcher is open.
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const news = useMemo(
    () => (state.status === "older-api" ? fromNotifications(notifications) : state.items).slice(0, 4),
    [state, notifications],
  );
  const unreachable = state.status === "unreachable";

  return (
    <section className="sg news-panel" aria-label="News" aria-busy={state.status === "loading"}>
      <div className="sgl news-label">
        <span>News</span>
        {news.length > 0 && <span className="news-count">{news.length}</span>}
      </div>
      {unreachable && (
        <div className="news-note" role="status">
          <span>
            {state.loadedAt
              ? `Could not refresh. Showing the news from ${clock(state.loadedAt)}.`
              : "Breeze news could not be reached."}
          </span>
          <button type="button" className="news-retry" onClick={load}>Try again</button>
        </div>
      )}
      <div className="news-list">
        {news.map((n, i) => (
          <NewsItem key={n.id || i} title={n.title} meta={when(n.created_at)} defaultOpen={i === 0}>
            {n.body && <p className="news-text">{n.body}</p>}
            <div className="news-from">{n.from}{n.role ? ` · ${n.role}` : ""}</div>
          </NewsItem>
        ))}
        {state.status === "loading" && <div className="news-empty">Loading news</div>}
        {news.length === 0 && (state.status === "ready" || state.status === "older-api") && (
          <div className="news-empty">No announcements yet.</div>
        )}
      </div>
    </section>
  );
}
