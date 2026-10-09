import { useEffect, useRef, useState } from "react";

// Small shared UI primitives used by BreezeApp.jsx and the extracted feature
// pages (SocialPage, GiftsPage, etc). Kept in their own file to avoid
// circular imports between BreezeApp.jsx and the pages it renders.

/**
 * The only way ads are embedded in the launcher.
 *
 * Ad frames run Google's ad script and whatever creative it serves. Without a
 * sandbox the frame shared the launcher's origin, so that third-party code could
 * reach window.top and the launcher's native command bridge, which includes
 * downloading and running an installer. The sandbox gives the frame an opaque
 * origin instead. Do not add allow-same-origin: that one token undoes the whole
 * point. allow-popups and allow-popups-to-escape-sandbox are what ad click-through
 * needs.
 */
// Google AdSense is for websites: its policies do not allow its ads inside a
// desktop application, and an app ad request for the site's publisher id puts
// the breezeclient.net AdSense account at risk. So the launcher shows no ads
// (2026-10-09): every AdFrame renders nothing and the Rewards tab, which
// exists to watch them, is hidden. Ads belong on the website. If a network
// made for desktop apps is ever added, it goes behind this switch.
export const LAUNCHER_ADS = false;

export function AdFrame(props) {
  return LAUNCHER_ADS ? <AdFrameInner {...props} /> : null;
}

function AdFrameInner({ src = "/ads.html", title = "Breeze partner", className = "ad-frame side-ad", style }) {
  const holderRef = useRef(null);
  // An ad frame is a whole document running Google's ad script, which loads
  // further frames of its own. The rewards page has seven of them, and the ones
  // scrolled out of view cost exactly as much as the visible ones. The frame is
  // created when it comes near the viewport and dropped when it leaves, so what
  // is running matches what the player can actually see.
  const [onScreen, setOnScreen] = useState(typeof IntersectionObserver !== "function");

  useEffect(() => {
    const holder = holderRef.current;
    if (!holder || typeof IntersectionObserver !== "function") return undefined;
    const observer = new IntersectionObserver(
      ([entry]) => setOnScreen(entry.isIntersecting),
      // A little ahead of the scroll, so a slot is filled before it is read.
      { rootMargin: "150px" },
    );
    observer.observe(holder);
    return () => observer.disconnect();
  }, []);

  return (
    <div ref={holderRef} className={className} style={style}>
      {onScreen && (
        <iframe
          className="ad-frame-inner"
          title={title}
          src={src}
          sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"
          referrerPolicy="no-referrer"
        />
      )}
    </div>
  );
}

export function Panel({ title, children }) {
  return <div className="sg"><div className="sgl">{title}</div><div className="scd">{children}</div></div>;
}

export function Row({ title, desc, action }) {
  return <div className="sr"><div className="si"><div className="sn">{title}</div><div className="sd">{desc}</div></div>{action}</div>;
}

export function Stat({ label, value, sub }) {
  return <div className="sc"><div className="sc-l">{label}</div><div className="sc-v">{value}</div><div className="sc-s">{sub}</div></div>;
}
