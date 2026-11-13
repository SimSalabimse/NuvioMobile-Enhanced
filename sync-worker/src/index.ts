import type { SyncEnv } from "./env";
import { fail } from "./http";
import { route } from "./router";

export default {
  async fetch(request: Request, env: SyncEnv): Promise<Response> {
    try {
      return await route(request, env);
    } catch (error) {
      const name = error instanceof Error ? error.name : "Error";
      console.error(JSON.stringify({ event: "request_failed", name }));
      return fail(500, "internal", "Internal error");
    }
  },
} satisfies ExportedHandler<SyncEnv>;
