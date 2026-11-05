declare module "@veerone/galileo-glass-ui" {
  import type {
    ForwardRefExoticComponent,
    HTMLAttributes,
    ReactNode,
    RefAttributes,
    RefObject,
  } from "react";

  export type GlassProps = HTMLAttributes<HTMLDivElement> & {
    className?: string;
    children?: ReactNode;
    interactive?: boolean;
    hoverLift?: boolean;
    focusRing?: boolean;
    press?: boolean;
  };

  export const Glass: ForwardRefExoticComponent<
    GlassProps & RefAttributes<HTMLDivElement>
  >;

  export function initializeAuraGlass(
    config?: Record<string, unknown> & {
      defaultTheme?: "light" | "dark" | "glass";
      qualityTier?: "low" | "medium" | "high" | "ultra" | "auto";
      reducedMotion?: boolean;
      animations?: {
        enabled: boolean;
        duration: number;
        easing: string;
      };
      performance?: {
        targetFPS: number;
        memoryLimit: number;
        autoOptimize: boolean;
      };
    },
  ): Promise<unknown>;

  export function useGlassParallax<T extends HTMLElement>(
    targetRef: RefObject<T | null>,
    options?: {
      strength?: number;
      enabled?: boolean;
    },
  ): void;
}
