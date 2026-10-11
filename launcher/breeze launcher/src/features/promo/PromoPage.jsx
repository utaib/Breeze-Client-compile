import PromoCodeManager from "../../components/PromoCodeManager";

export default function PromoPage({ I, token, notify }) {
  return (
    <div className="sv page-enter">
      <div className="vtl"><I.Ticket /> Promo Code Management</div>
      <PromoCodeManager
        token={token}
        onNotify={(type, message) => notify(type === "!" ? "!" : "ok", message)}
        onLog={() => {}}
      />
    </div>
  );
}
