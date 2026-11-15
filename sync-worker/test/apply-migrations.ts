import { applyD1Migrations } from "cloudflare:test";
import { env } from "cloudflare:workers";

interface MigrationEnv {
  DB: D1Database;
  TEST_MIGRATIONS: Parameters<typeof applyD1Migrations>[1];
}

const testEnv = env as unknown as MigrationEnv;
await applyD1Migrations(testEnv.DB, testEnv.TEST_MIGRATIONS);
