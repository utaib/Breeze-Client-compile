import type { ReactNode } from "react";

export type FMTrack = {
  id: string;
  title: string;
  artist: string;
  artwork: string | null;
  duration: number;
  streamUrl: string;
};

export type BreezeFMState = {
  tracks: FMTrack[];
  current: FMTrack | null;
  index: number;
  playing: boolean;
  loading: boolean;
  error: string;
  heading: string;
  progress: number;
  duration: number;
  volume: number;
  query: string;
  setQuery: (value: string) => void;
  search: () => void;
  playAt: (index: number) => void;
  toggle: () => void;
  next: () => void;
  prev: () => void;
  /** Seek to a fraction of the track, 0 to 1. */
  seekTo: (fraction: number) => void;
  setVolume: (volume: number) => void;
  /** Loads the trending list once per session; repeat calls are ignored. */
  loadTrending: () => void;
};

/**
 * Playback state shared across the whole app. The audio element is mounted by
 * the provider above the router, so navigating between pages never interrupts
 * music.
 */
export declare function useBreezeFM(): BreezeFMState;

export declare function BreezeFMProvider(props: { children?: ReactNode }): JSX.Element;
