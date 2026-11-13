import path from "node:path";
import { readFileSync } from "node:fs";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

const cryptoVector = JSON.parse(
  readFileSync(new URL("../docs/sync/test-vectors/v1/crypto.json", import.meta.url), "utf8"),
) as { tokenHashHex: string };

export default defineConfig(async () => {
  const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            ALLOWLIST_TOKEN_HASHES: cryptoVector.tokenHashHex,
            RATE_LIMIT_PER_WINDOW: "8",
          },
        },
      }),
    ],
    server: {
      fs: {
        allow: [path.resolve(import.meta.dirname, "..")],
      },
    },
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
      testTimeout: 180_000,
      hookTimeout: 180_000,
    },
  };
});
