import { priceInWc } from "../../services/breezeApi";

/** A price in Wind Charges, or Free. */
export default function WcPrice({ priceUsd }) {
  const wc = priceInWc(priceUsd);
  if (wc <= 0) return <span className="free-label">Free</span>;
  return <><span className="wc-ico" aria-hidden="true" /><span className="wc-num">{wc.toLocaleString()}</span><span className="sr-only"> Wind Charges</span></>;
}
