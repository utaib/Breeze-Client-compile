import type { ReactNode } from "react";

/** Options for a Breeze confirmation dialog. A bare string is also accepted. */
export type ConfirmOptions = {
  title?: string;
  body?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Styles the dialog for a destructive action and defaults the label to "Delete". */
  danger?: boolean;
};

/**
 * Returns an await-able confirm. Resolves true when the user confirms.
 * Replaces window.confirm, which renders unstyled OS chrome and blocks the
 * webview thread.
 */
export declare function useConfirm(): (options: ConfirmOptions | string) => Promise<boolean>;

export declare function ConfirmProvider(props: { children?: ReactNode }): JSX.Element;
