const hasUnsafeCharacters = (value: string) => Array.from(value).some(char => char === "\\" || char.charCodeAt(0) <= 32);
export const LOCAL_TOKEN_KEY = "aisocialgame_token";
export const LOCAL_SSO_STATE_KEY = "aisocialgame_sso_state";
export const LOCAL_RETURN_TO_KEY = "aisocialgame_return_to";
export const readStorage = (key: string) => { try { return sessionStorage.getItem(key); } catch { return null; } };
export const writeStorage = (key: string, value: string | null) => { try { if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, value); } catch { /* In-memory sessions still work. */ } };

export function safeReturnTo(value: string | null | undefined): string {
  if (!value || !value.startsWith("/") || value.startsWith("//") || hasUnsafeCharacters(value)) return "/";
  let decoded: string;
  try { decoded = decodeURIComponent(value); } catch { return "/"; }
  if (decoded.startsWith("//") || hasUnsafeCharacters(decoded)) return "/";
  const url = new URL(value, window.location.origin);
  if (url.origin !== window.location.origin || /^\/(sso|admin)(\/|$)/.test(new URL(decoded, window.location.origin).pathname)) return "/";
  const path = new URL(decoded, window.location.origin).pathname;
  if (!/^\/(?:$|guide\/?$|profile\/?$|community\/?$|ai-chat\/?$|rankings\/?$|achievements\/?$|replays\/?$|(?:game|create|replay)\/[^/]+\/?$|(?:room|spectate)\/[^/]+\/[^/]+\/?$)/.test(path)) return "/";
  return `${url.pathname}${url.search}${url.hash}`;
}
export function consumeReturnTo() { const path = safeReturnTo(readStorage(LOCAL_RETURN_TO_KEY)); writeStorage(LOCAL_RETURN_TO_KEY, null); return path; }
