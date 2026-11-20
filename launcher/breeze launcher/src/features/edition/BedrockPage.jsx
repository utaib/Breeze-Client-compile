import { BEDROCK_LIMITS, BEDROCK_PLAN } from "./editions";

/**
 * Bedrock Edition, planned: what is planned, what will not be possible,
 * and the way back to Java. Nothing here launches, installs or reads anything
 * (docs/EDITIONS_AND_SOURCES.md).
 */
export default function BedrockPage({ onBack }) {
  return (
    <div className="sv page-enter ed-page" aria-labelledby="ed-title">
      <div className="ed-hero">
        <span className="ed-soon ed-soon-lg">Planned</span>
        <h1 id="ed-title" className="ed-title">Bedrock Edition</h1>
        <p className="ed-lead">
          Breeze is built for Java Edition today. Bedrock support is planned for a later
          release, on Windows. Your Breeze account, cosmetics, themes and layout will work
          in both.
        </p>
        <button type="button" className="modal-buy ed-back" onClick={onBack}>
          Back to Java Edition
        </button>
      </div>

      <section className="ed-section" aria-labelledby="ed-plan">
        <h2 id="ed-plan" className="ed-h2">Planned</h2>
        <ol className="ed-plan">
          {BEDROCK_PLAN.map((step) => (
            <li key={step.title}>
              <h3>{step.title}</h3>
              <p>{step.text}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="ed-section" aria-labelledby="ed-limits">
        <h2 id="ed-limits" className="ed-h2">Good to know</h2>
        <ul className="ed-limits">
          {BEDROCK_LIMITS.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </section>
    </div>
  );
}
