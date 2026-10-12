// Centralized environment-driven URLs. Reading these here (instead of scattering
// import.meta.env lookups across feature files) keeps a future domain or API
// move to a single place.
export const WEBSITE_URL = import.meta.env.VITE_BREEZE_WEBSITE_URL || "https://breezeclient.pages.dev";
export const TERMS_URL = import.meta.env.VITE_BREEZE_TERMS_URL || `${WEBSITE_URL}/terms.html`;
export const PRIVACY_URL = import.meta.env.VITE_BREEZE_PRIVACY_URL || `${WEBSITE_URL}/privacy.html`;
