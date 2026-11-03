import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';
import { CLIENT_PUBLIC_KEYS, FINGERPRINT_SECRET } from './test/client-fixture';

export default defineConfig({
  plugins: [cloudflareTest({ wrangler: { configPath: './wrangler.jsonc' }, miniflare: { bindings: {
    // Synthetic fixtures only. Never load developer .dev.vars or real credentials in tests.
    CLIENT_PUBLIC_KEYS, FINGERPRINT_SECRET, UPSTREAM_MODE: 'MOCK',
    LIVE_AI_ENABLED: 'true',
    BILLING_POLICY: "{\"id\":\"TEST_ONLY_V1\",\"currency\":\"CNY\",\"verification\":\"MOCK\",\"inputMicrosPerMillion\":\"1000000\",\"cachedInputMicrosPerMillion\":\"100000\",\"outputMicrosPerMillion\":\"2000000\",\"dailyBudgetMicros\":\"100000000\",\"totalBudgetMicros\":\"1000000000\"}",
  } } })],
  test: { include: ['test/**/*.test.ts'], testTimeout: 10000, hookTimeout: 15000, reporters: ['default','json'], outputFile: {json:'./.wrangler/vitest-results.json'} },
});
