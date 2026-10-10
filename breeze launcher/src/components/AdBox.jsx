import { openExternalUrl } from "../services/nativeBridge";

/**
 * An ad box the owner fills from the admin panel (API: GET /ads/boxes).
 *
 * AdSense does not allow its ads in desktop apps, so in the launcher a box
 * shows only an image the owner uploaded, and nothing at all otherwise: no
 * empty frame, no placeholder.
 */
export default function AdBox({ box, className = "" }) {
  if (!box || box.mode !== "image" || !box.image_url) return null;
  const image = <img src={box.image_url} alt={box.alt || ""} className="ad-box-img" draggable={false} />;
  return (
    <div className={`ad-box ${className}`}>
      {box.link ? (
        <a
          href={box.link}
          onClick={(event) => {
            event.preventDefault();
            openExternalUrl(box.link).catch(() => {});
          }}
        >
          {image}
        </a>
      ) : image}
    </div>
  );
}
