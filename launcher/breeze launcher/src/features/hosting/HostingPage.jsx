// Server hosting is intentionally parked for the 1.0.0 release: the previous
// local-hosting prototype exposed unfinished behavior, and the real product
// (in-game hosting through the Breeze mod, plus future Breeze Cloud hosting)
// isn't production-ready yet. Ship a clean Coming Soon instead of a mockup.
// The Rust local-server commands remain in src-tauri for when this returns.
const I = {
  Server: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="8" rx="2" /><rect x="2" y="14" width="20" height="8" rx="2" /><line x1="6" y1="6" x2="6.01" y2="6" /><line x1="6" y1="18" x2="6.01" y2="18" /></svg>,
};

export default function HostingPage() {
  return (
    <div className="sv page-enter" style={{ display: "flex" }}>
      <div className="coming-soon">
        <div className="coming-soon-icon"><I.Server /></div>
        <span className="coming-soon-badge">Coming soon</span>
        <div className="coming-soon-title">Server Hosting</div>
        <div className="coming-soon-sub">
          This isn't implemented yet. I'll implement it later.
          For now you can host a world in game through the Breeze mod, or use one
          of the hosts below.
        </div>

        {/* Space for a hosting partner. Sized and bordered here so the slot
            looks deliberate while empty rather than like a failed image, and so
            dropping artwork in later needs no layout work. */}
        <div className="hosting-promo" />
      </div>
    </div>
  );
}
