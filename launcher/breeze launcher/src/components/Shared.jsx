// Small shared UI primitives used by BreezeApp.jsx and the extracted feature
// pages (SocialPage, GiftsPage, etc). Kept in their own file to avoid
// circular imports between BreezeApp.jsx and the pages it renders.

export function Panel({ title, children }) {
  return <div className="sg"><div className="sgl">{title}</div><div className="scd">{children}</div></div>;
}

export function Row({ title, desc, action }) {
  return <div className="sr"><div className="si"><div className="sn">{title}</div><div className="sd">{desc}</div></div>{action}</div>;
}

export function Stat({ label, value, sub }) {
  return <div className="sc"><div className="sc-l">{label}</div><div className="sc-v">{value}</div><div className="sc-s">{sub}</div></div>;
}
