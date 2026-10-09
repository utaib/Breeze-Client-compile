import { useState } from "react";
import { I } from "../ui/icons";

/**
 * Notification centre with real history management.
 *
 * Notifications could previously only be marked read, never removed, so routine
 * sign-in notices piled up with no way to clear them. This adds per-item
 * dismissal, a multi-select mode for bulk removal, and Clear all.
 */
export default function NotificationPanel({ notifications, onClose, onDismiss, onDismissMany, onMarkAllRead, busy }) {
  const items = Array.isArray(notifications) ? notifications : [];
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState(() => new Set());

  const unread = items.filter((n) => !n.read_at).length;
  const allSelected = items.length > 0 && selected.size === items.length;

  const toggle = (id) => {
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const exitSelect = () => {
    setSelecting(false);
    setSelected(new Set());
  };

  const dismissSelected = async () => {
    if (!selected.size) return;
    await onDismissMany?.({ ids: [...selected] });
    exitSelect();
  };

  const clearAll = async () => {
    await onDismissMany?.({ scope: "all" });
    exitSelect();
  };

  return (
    <div className="notification-panel page-enter">
      <div className="panel-head">
        <div>
          <div className="panel-title">Notifications</div>
          <div className="panel-sub">
            {items.length === 0
              ? "Nothing here"
              : selecting
                ? `${selected.size} selected`
                : `${items.length} total${unread ? `, ${unread} unread` : ""}`}
          </div>
        </div>
        <button className="mini-btn" onClick={onClose} title="Close"><I.X /></button>
      </div>

      {items.length > 0 && (
        <div className="notification-actions">
          {selecting ? (
            <>
              <button
                className="notif-act"
                onClick={() => setSelected(allSelected ? new Set() : new Set(items.map((n) => n.id)))}
              >
                {allSelected ? "Deselect all" : "Select all"}
              </button>
              <button className="notif-act danger" disabled={!selected.size || busy} onClick={dismissSelected}>
                Dismiss {selected.size || ""}
              </button>
              <button className="notif-act" onClick={exitSelect}>Cancel</button>
            </>
          ) : (
            <>
              <button className="notif-act" onClick={() => setSelecting(true)}>Select</button>
              {unread > 0 && (
                <button className="notif-act" disabled={busy} onClick={() => onMarkAllRead?.()}>Mark all read</button>
              )}
              <button className="notif-act danger" disabled={busy} onClick={clearAll}>Clear all</button>
            </>
          )}
        </div>
      )}

      <div className="notification-list">
        {items.length ? (
          items.map((item) => {
            const isSel = selected.has(item.id);
            return (
              <div
                key={item.id}
                className={`notification-card ${item.read_at ? "" : "unread"} ${selecting ? "selectable" : ""} ${isSel ? "selected" : ""}`}
                onClick={selecting ? () => toggle(item.id) : undefined}
              >
                {selecting && <span className={`notif-check ${isSel ? "on" : ""}`}>{isSel ? <I.Check /> : null}</span>}
                <div className="notif-main">
                  <div className="notification-type">{item.type || "activity"}</div>
                  <div className="notification-title">{item.title}</div>
                  {item.body && <div className="notification-body">{item.body}</div>}
                </div>
                {!selecting && (
                  <button
                    className="notif-dismiss"
                    title="Dismiss"
                    disabled={busy}
                    onClick={(e) => { e.stopPropagation(); onDismiss?.(item.id); }}
                  >
                    <I.X />
                  </button>
                )}
              </div>
            );
          })
        ) : (
          <div className="empty-panel">No notifications yet.</div>
        )}
      </div>
    </div>
  );
}
