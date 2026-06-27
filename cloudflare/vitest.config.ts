import { defineConfig } from 'vitest/config';

// The route/handler tests run in plain Node (Hono is runtime-agnostic) with a
// small fake D1 binding. For full Workers-runtime integration tests, swap in
// @cloudflare/vitest-pool-workers.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
  },
});
