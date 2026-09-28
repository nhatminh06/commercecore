import type { RequestErrorBody } from "@/lib/types";

const API_BASE_URL = "/api/commercecore";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }


  get code(): string | undefined {
    return (this.body as RequestErrorBody | undefined)?.code;
  }
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  const headers = new Headers(options.headers);

  if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE_URL}${normalizedPath}`, {
    ...options,
    headers,
  });
  const contentType = response.headers.get("content-type") ?? "";
  const body: unknown = response.status === 204
    ? undefined
    : contentType.includes("application/json")
      ? await response.json()
      : await response.text();

  if (!response.ok) {
    const details = body as RequestErrorBody;
    const message =
      details?.message || details?.error || `CommerceCore request failed (${response.status})`;
    throw new ApiError(message, response.status, body);
  }

  return body as T;
}
