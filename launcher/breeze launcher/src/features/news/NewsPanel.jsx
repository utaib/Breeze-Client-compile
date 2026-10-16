import { useEffect, useMemo, useState } from "react";
import { getNews } from "../../services/breezeApi";
import { RELEASE_NOTES } from "./releaseNotes";

/**
 * News on the play page: the team's announcements (GET /announcements) and
 * what this launcher release changed. Announcements also arrive on the bell
 * as notifications; here they stay readable after the notification is
 * cleared, and reach accounts made after they were sent.
 *
 * An API older than 1.0.38 has no /announcements: the panel then shows the
 * announcement notifications this account still has.
 */
const ANNOUNCEMENT_TYPE = /^Announcement from (.+?)(?: \((.+)\))?$/;

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

function NewsItem({ kind, title, meta, children, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <article className={`news-item ${open ? "open" : ""}`}>
      <button type="button" className="news-head" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className={`news-kind ${kind === "Update" ? "update" : ""}`}>{kind}</span>
        <span className="news-when">{meta}</span>
        <span className="news-title">{title}</span>
      </button>
      <div className="news-body">{children}</div>
    </article>
  );
}

export function NewsPanel({ notifications, appVersion }) {
  const [items, setItems] = useState(null);
  const [fallback, setFallback] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const load = () => getNews()
      .then((list) => { if (!cancelled) { setItems(list); setFallback(false); } })
      .catch(() => { if (!cancelled) setFallback(true); });
    load();
    // New announcements show within a few minutes while the launcher is open.
    const timer = setInterval(load, 5 * 60 * 1000);
    return () => { cancelled = true; clearInterval(timer); };
  }, []);

  const news = useMemo(
    () => (fallback ? fromNotifications(notifications) : items || []).slice(0, 4),
    [fallback, items, notifications],
  );
  const notes = RELEASE_NOTES.version === appVersion ? RELEASE_NOTES : null;

  return (
    <section className="sg news-panel" aria-label="News">
      <div className="sgl news-label">
        <span>News</span>
        {news.length > 0 && <span className="news-count">{news.length}</span>}
      </div>
      <div className="news-list">
        {news.map((n, i) => (
          <NewsItem key={n.id || i} kind="Announcement" title={n.title} meta={when(n.created_at)} defaultOpen={i === 0}>
            {n.body && <p className="news-text">{n.body}</p>}
            <div className="news-from">{n.from}{n.role ? ` · ${n.role}` : ""}</div>
          </NewsItem>
        ))}
        {notes && (
          <NewsItem kind="Update" title={`What's new in ${notes.title}`} meta={when(notes.date)} defaultOpen={news.length === 0}>
            <ul className="news-points">
              {notes.points.map((p) => <li key={p}>{p}</li>)}
            </ul>
          </NewsItem>
        )}
        {!notes && news.length === 0 && (
          <div className="news-empty">Announcements from the Breeze team show here.</div>
        )}
      </div>
    </section>
  );
}
