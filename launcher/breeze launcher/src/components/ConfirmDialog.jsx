import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { I } from "../ui/icons";

/**
 * Promise-based confirmation dialogs in the Breeze visual language.
 *
 * `window.confirm` renders an OS chrome dialog that looks nothing like the
 * launcher, cannot be styled, and blocks the entire webview thread. This gives
 * the same await-able ergonomics without either problem:
 *
 *     const confirm = useConfirm();
 *     if (!(await confirm({ title: "Delete this cape?", danger: true }))) return;
 */
const ConfirmContext = createContext(null);

export function useConfirm() {
  const ctx = useContext(ConfirmContext);
  if (!ctx) {
    // Fall back rather than throwing: a missing provider should never take a
    // page down, and returning false is the safe answer for a destructive act.
    return async () => false;
  }
  return ctx;
}

export function ConfirmProvider({ children }) {
  const [request, setRequest] = useState(null);
  const resolverRef = useRef(null);
  const confirmBtnRef = useRef(null);

  const confirm = useCallback((options) => {
    const opts = typeof options === "string" ? { title: options } : options || {};
    return new Promise((resolve) => {
      resolverRef.current = resolve;
      setRequest({
        title: opts.title || "Are you sure?",
        body: opts.body || "",
        confirmLabel: opts.confirmLabel || (opts.danger ? "Delete" : "Confirm"),
        cancelLabel: opts.cancelLabel || "Cancel",
        danger: Boolean(opts.danger),
      });
    });
  }, []);

  const settle = useCallback((answer) => {
    setRequest(null);
    const resolve = resolverRef.current;
    resolverRef.current = null;
    resolve?.(answer);
  }, []);

  // Enter confirms, Escape cancels, matching what a native dialog would do.
  useEffect(() => {
    if (!request) return undefined;
    confirmBtnRef.current?.focus();
    const onKey = (event) => {
      if (event.key === "Escape") { event.preventDefault(); settle(false); }
      if (event.key === "Enter") { event.preventDefault(); settle(true); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [request, settle]);

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {request && (
        <div className="modal-backdrop confirm-backdrop" onClick={() => settle(false)}>
          <div
            className="modal confirm-modal"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="breeze-confirm-title"
            onClick={(event) => event.stopPropagation()}
          >
            <div className={`confirm-icon ${request.danger ? "danger" : ""}`}>
              {request.danger ? <I.X /> : <I.Shield />}
            </div>
            <div className="confirm-title" id="breeze-confirm-title">{request.title}</div>
            {request.body && <div className="confirm-body">{request.body}</div>}
            <div className="confirm-actions">
              <button className="btn" onClick={() => settle(false)}>{request.cancelLabel}</button>
              <button
                ref={confirmBtnRef}
                className={`btn ${request.danger ? "danger" : "accent"}`}
                onClick={() => settle(true)}
              >
                {request.confirmLabel}
              </button>
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  );
}
