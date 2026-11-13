import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

const ENROLL_SECRET = "test-enroll-not-a-real-secret";

export default defineConfig(async () => {
  const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            ENROLL_SECRET,
            RATE_LIMIT_PER_WINDOW: "20",
            MAX_SPACE_BLOBS: "4",
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
