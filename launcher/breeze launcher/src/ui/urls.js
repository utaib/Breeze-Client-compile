// Centralized environment-driven URLs. Reading these here (instead of scattering
// import.meta.env lookups across feature files) keeps future domain/API migration
// to a single place, see BREEZE_MASTER_VISION.md.
export const WEBSITE_URL = import.meta.env.VITE_BREEZE_WEBSITE_URL || "https://breezeclient.pages.dev";
export const TERMS_URL = import.meta.env.VITE_BREEZE_TERMS_URL || `${WEBSITE_URL}/terms.html`;
export const PRIVACY_URL = import.meta.env.VITE_BREEZE_PRIVACY_URL || `${WEBSITE_URL}/privacy.html`;
