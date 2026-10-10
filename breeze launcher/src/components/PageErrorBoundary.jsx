import { Component } from "react";

/**
 * Keeps one failing page from taking down the whole launcher.
 *
 * React unmounts the entire tree when a render throws, so before this existed a
 * single bad read (a null list, a missing field) turned into a blank window
 * with no way back. Now the failure is contained to the page body: the sidebar,
 * header and navigation keep working, and the user can switch pages or retry.
 *
 * This is a safety net, not a substitute for guarding data. Anything it catches
 * is still a bug worth fixing at the source.
 */
export default class PageErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // Surfaced in devtools so a crash is diagnosable rather than silent.
    console.error(`[Breeze] "${this.props.pageName || "page"}" failed to render`, error, info?.componentStack);
  }

  componentDidUpdate(prevProps) {
    // Navigating away from a broken page clears the error, so the user is not
    // stuck looking at the fallback after switching tabs.
    if (this.state.error && prevProps.pageKey !== this.props.pageKey) {
      this.setState({ error: null });
    }
  }

  render() {
    if (!this.state.error) return this.props.children;
    const name = this.props.pageName || "This page";
    return (
      <div className="sv page-enter" style={{ display: "flex", alignItems: "center", justifyContent: "center", flex: 1 }}>
        <div style={{ textAlign: "center", maxWidth: 420 }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: "var(--text-primary)", marginBottom: 6 }}>
            {name} could not load
          </div>
          <div style={{ fontSize: 11, color: "var(--text-faint)", marginBottom: 14, lineHeight: 1.5 }}>
            Something went wrong while drawing this section. The rest of Breeze is unaffected,
            so you can keep using the launcher.
          </div>
          <button className="btn accent" onClick={() => this.setState({ error: null })}>
            Try again
          </button>
        </div>
      </div>
    );
  }
}
