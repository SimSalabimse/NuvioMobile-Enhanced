const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

export function fail(status: number, error: string, message: string): Response {
  return json({ error, message }, status);
}

export function tooMany(retryAfterSeconds: number): Response {
  return new Response(JSON.stringify({ error: "rate_limited", message: "Too many requests" }), {
    status: 429,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "retry-after": String(retryAfterSeconds),
    },
  });
}
