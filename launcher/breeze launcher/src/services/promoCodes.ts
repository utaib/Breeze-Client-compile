


const DEFAULT_API_BASE_URL = "https://api.breezeclient.net";
const configuredApiBaseUrl = import.meta.env.VITE_BREEZE_API_URL?.replace(/\/$/, "");
const API_BASE_URLS = Array.from(
  new Set([configuredApiBaseUrl, DEFAULT_API_BASE_URL].filter(Boolean))
) as string[];

export type PromoCode = {
  id: string;
  code: string;
  discount_percent: number;
  usage_limit: number | null;
  times_used: number;
  is_active: boolean;
  owner_uuid?: string | null;
  created_at?: string;
};

export type PromoValidation = {
  valid: true;
  discount_percent: number;
};


export async function createPromoCode(input: {
  token: string;
  code: string;
  discount_percent: number;
  usage_limit?: number | null;
}): Promise<PromoCode> {
  const body = await apiRequest<{ promo: PromoCode }>("/promo-codes", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${input.token}` },
    body: JSON.stringify({
      code: input.code,
      discount_percent: input.discount_percent,
      usage_limit: input.usage_limit ?? null,
    }),
  });
  return body.promo;
}


export async function listPromoCodes(token: string): Promise<PromoCode[]> {
  const body = await apiRequest<{ promo_codes: PromoCode[] }>("/promo-codes", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return body.promo_codes ?? [];
}


export async function updatePromoCode(input: {
  token: string;
  id: string;
  discount_percent?: number;
  usage_limit?: number | null;
  is_active?: boolean;
}): Promise<void> {
  const { token, id, ...patch } = input;
  await apiRequest<{ message: string }>(`/promo-codes/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(patch),
  });
}


export async function deactivatePromoCode(token: string, id: string): Promise<void> {
  await apiRequest<{ message: string }>(`/promo-codes/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
}


export async function validatePromoCode(token: string, code: string): Promise<PromoValidation> {
  return apiRequest<PromoValidation>("/promo-codes/validate", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ code: code.trim() }),
  });
}


async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  let lastError: unknown;
  for (const baseUrl of API_BASE_URLS) {
    try {
      const response = await fetch(`${baseUrl}${path}`, init);
      const text = await response.text();
      let parsed: unknown = null;
      if (text) { try { parsed = JSON.parse(text); } catch {  } }
      if (!response.ok) {
        const msg = (parsed && typeof parsed === "object" && parsed !== null && "error" in parsed
          ? String((parsed as { error?: unknown }).error ?? "")
          : "") || `Request failed with ${response.status}`;
        throw new Error(msg);
      }
      if (parsed && typeof parsed === "object" && "success" in parsed) {
        const envelope = parsed as { success?: boolean; error?: string };
        if (envelope.success === false) throw new Error(envelope.error || "Request rejected");
        const { success: _s, ...payload } = envelope as Record<string, unknown>;
        return payload as T;
      }
      return (parsed ?? {}) as T;
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError instanceof Error ? lastError : new Error("Promo code request failed");
}