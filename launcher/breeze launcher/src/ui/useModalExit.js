import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Let a modal animate out before its parent unmounts it.
 *
 * Every modal in Breeze is rendered as `{show && <Modal onClose={...} />}`, so
 * the moment onClose fires the element is gone from the DOM and no exit
 * animation can play. React offers no hook for "about to unmount", and CSS
 * cannot transition a node that no longer exists.
 *
 * The fix belongs inside the modal rather than in all eight parents: swallow
 * the first close, flag `closing` so the markup can carry a class, and call the
 * real onClose once the animation has run. Parents keep working exactly as they
 * did, and every existing onClose call site animates out for free because the
 * returned function replaces it by the same name.
 *
 * Usage:
 *   function MyModal({ onClose: onCloseProp }) {
 *     const { closing, close: onClose } = useModalExit(onCloseProp);
 *     return <div className={`modal-backdrop ${closing ? "closing" : ""}`} onClick={onClose}>…
 */

/** Must match the .modal-backdrop.closing animation duration in BreezeV2.css. */
const EXIT_MS = 180;

export default function useModalExit(onClose) {
  const [closing, setClosing] = useState(false);
  const timerRef = useRef(null);
  const firedRef = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  // A modal can be unmounted by something other than its own close button (a
  // route change, a parent re-render). Clearing here stops the timer calling
  // onClose on a component that is already gone.
  useEffect(() => () => clearTimeout(timerRef.current), []);

  const close = useCallback((...args) => {
    // Guard against a double click on the close button queueing two timers and
    // calling the parent's handler twice, which in a few of these modals would
    // fire the same request twice.
    if (firedRef.current) return;
    firedRef.current = true;
    setClosing(true);

    const reduce =
      typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

    timerRef.current = setTimeout(() => {
      onCloseRef.current?.(...args);
    }, reduce ? 0 : EXIT_MS);
  }, []);

  return { closing, close };
}
