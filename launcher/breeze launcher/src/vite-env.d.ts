
interface ImportMetaEnv {
  readonly DEV: boolean;
  readonly PROD: boolean;
  readonly MODE: string;
  readonly BASE_URL: string;
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_BREEZE_API_URL?: string;
  readonly VITE_BREEZE_WEBSITE_URL?: string;
  readonly VITE_BREEZE_TERMS_URL?: string;
  readonly VITE_BREEZE_PRIVACY_URL?: string;
  readonly VITE_BREEZE_VERSION_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/**
 * The app version, replaced at build time by Vite from package.json.
 *
 * Declared rather than imported so there is exactly one place the version can
 * come from. The previous hand-written constant went three releases stale and
 * made the launcher report an update available forever.
 */
declare const __APP_VERSION__: string;
