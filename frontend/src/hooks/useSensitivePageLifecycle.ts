import { useCallback, useEffect, useRef, useState } from "react";

// A response belongs to the visible page generation that requested it.
export function useSensitivePageLifecycle(clear: () => void) {
  const epoch = useRef(0);
  const active = useRef(true);
  const clearRef = useRef(clear);
  clearRef.current = clear;
  const [version, setVersion] = useState(0);
  const invalidate = useCallback(() => {
    epoch.current += 1;
    clearRef.current();
    setVersion(epoch.current);
  }, []);
  const begin = useCallback(() => active.current ? epoch.current : null, []);
  const current = useCallback((request: number | null) => request !== null && active.current && request === epoch.current, []);
  useEffect(() => {
    active.current = true;
    const hide = () => { active.current = false; invalidate(); };
    const show = () => { active.current = true; invalidate(); };
    const logout = () => invalidate();
    window.addEventListener("pagehide", hide);
    window.addEventListener("pageshow", show);
    window.addEventListener("admin-auth-reset", logout);
    return () => {
      active.current = false;
      epoch.current += 1;
      window.removeEventListener("pagehide", hide);
      window.removeEventListener("pageshow", show);
      window.removeEventListener("admin-auth-reset", logout);
    };
  }, [invalidate]);
  return { begin, current, invalidate, version };
}
