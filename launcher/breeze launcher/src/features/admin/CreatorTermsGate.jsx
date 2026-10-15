import { useCallback, useEffect, useRef, useState } from "react";
import { acceptCreatorTerms, getCreatorTerms } from "../../services/breezeApi";

/**
 * Creators have to accept the Breeze Creator Terms before the Admin page opens.
 *
 * The page below (children) is only ever rendered once the API says the current
 * version is accepted. While the answer is loading, or if it fails, nothing of
 * the page shows: the gate fails closed. The API refuses creator requests from
 * a creator who has not accepted as well, so this screen cannot be skipped by
 * calling the API directly.
 *
 * Staff (admin and owner) and everyone else pass straight through: `required`
 * is false for them and no request is made.
 */
export default function CreatorTermsGate({ required, token, onAccepted, onLeave, children }) {
  const [view, setView] = useState({ status: "loading" });
  const [agree, setAgree] = useState(false);
  const [readAll, setReadAll] = useState(false);
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState("");
  const scrollRef = useRef(null);

  const load = useCallback(async () => {
    if (!required) return;
    setView({ status: "loading" });
    setProblem("");
    setAgree(false);
    setReadAll(false);
    try {
      const t = await getCreatorTerms(token);
      setView(t.accepted || !t.required ? { status: "ok" } : { status: "needed", terms: t });
    } catch (e) {
      setView({ status: "error", message: e?.message || "Could not check the creator terms." });
    }
  }, [required, token]);

  useEffect(() => { load(); }, [load]);

  const checkScroll = useCallback(() => {
    const el = scrollRef.current;
    if (el && el.scrollTop + el.clientHeight >= el.scrollHeight - 12) setReadAll(true);
  }, []);

  useEffect(() => {
    if (view.status !== "needed") return undefined;
    const id = requestAnimationFrame(checkScroll);
    return () => cancelAnimationFrame(id);
  }, [view.status, checkScroll]);

  async function accept() {
    if (view.status !== "needed" || !agree || saving) return;
    setSaving(true);
    setProblem("");
    try {
      await acceptCreatorTerms(token, view.terms.version);
      setView({ status: "ok" });
      onAccepted?.();
    } catch (e) {
      const message = e?.message || "Could not save your acceptance. Try again.";
      setProblem(message);
      if (/changed/i.test(message)) load();
    } finally {
      setSaving(false);
    }
  }

  if (!required || view.status === "ok") return children;

  return (
    <div className="sv page-enter terms-gate" role="region" aria-label="Breeze Creator Terms">
      {view.status === "loading" && (
        <div className="terms-card">
          <h2 className="terms-title">Checking your creator access</h2>
          <p className="terms-lead">One moment.</p>
        </div>
      )}

      {view.status === "error" && (
        <div className="terms-card">
          <h2 className="terms-title">Could not check the creator terms</h2>
          <p className="terms-lead">{view.message}</p>
          <div className="terms-actions">
            <button className="btn accent" onClick={load}>Try again</button>
            <button className="btn" onClick={onLeave}>Back</button>
          </div>
        </div>
      )}

      {view.status === "needed" && (
        <div className="terms-card">
          <h2 className="terms-title">Breeze Creator Terms</h2>
          <p className="terms-lead">
            Read these terms and accept them to open your creator dashboard. Version {view.terms.version}.
          </p>
          <div className="terms-scroll" ref={scrollRef} onScroll={checkScroll} tabIndex={0}>
            <ol className="terms-list">
              {view.terms.terms.map((section) => (
                <li key={section.title}>
                  <h3>{section.title}</h3>
                  <p>{section.body}</p>
                </li>
              ))}
            </ol>
          </div>
          <label className={`terms-agree ${readAll ? "" : "off"}`}>
            <input
              type="checkbox"
              checked={agree}
              disabled={!readAll || saving}
              onChange={(e) => setAgree(e.target.checked)}
            />
            <span>I have read and accept the Breeze Creator Terms</span>
          </label>
          {!readAll && <p className="terms-hint">Scroll to the end of the terms to unlock this.</p>}
          {problem && <p className="terms-problem" role="alert">{problem}</p>}
          <div className="terms-actions">
            <button className="btn accent" disabled={!agree || saving} onClick={accept}>
              {saving ? "Saving" : "Accept and continue"}
            </button>
            <button className="btn" onClick={onLeave} disabled={saving}>Not now</button>
          </div>
        </div>
      )}
    </div>
  );
}
