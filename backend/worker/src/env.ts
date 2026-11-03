export interface Bindings {
  REQUEST_GATE: DurableObjectNamespace;
  CLIENT_PUBLIC_KEYS: string;
  FINGERPRINT_SECRET: string;
  LIVE_AI_ENABLED: string;
  UPSTREAM_MODE: 'MOCK' | 'LIVE';
  DEEPSEEK_MODEL: string;
  BILLING_POLICY?: string;
}
