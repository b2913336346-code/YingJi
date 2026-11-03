// Synthetic signing identities only; private keys are never imported by production source.
export const CLIENT_ID='TEST_ONLY_ACCESS_0000000000000000000000000000';
export const ROTATED_ID='TEST_ONLY_ROTATED_ACCESS_000000000000000000000';
export const PRIVATE_JWK={"kty":"EC","x":"vvCRwX_ckPNgNsoxFmTmb1x8P-yw-GrUoB_TToj2cYc","y":"WGxHnb-hzP0vtUHIvejbgvTOgvX3KEj1NiGvJSY93wQ","crv":"P-256","d":"n0-mHnOKp9uJhk94UYGYS3Kp9u3vpxwztieCmMIwglQ"};
export const PUBLIC_JWK={"kty":"EC","x":"vvCRwX_ckPNgNsoxFmTmb1x8P-yw-GrUoB_TToj2cYc","y":"WGxHnb-hzP0vtUHIvejbgvTOgvX3KEj1NiGvJSY93wQ","crv":"P-256"};
export const ROTATED_PRIVATE_JWK={"kty":"EC","x":"WWI973MIvGTyoNxc2VZxZUjIY9QaErC19zSg5en2Png","y":"RDJ6p6xz3hJ_e8VtBwqZrfQC75fQ7hOtNJnCj173Qg4","crv":"P-256","d":"VBVkNlaIXPaGkY0Rjl4ntMIrxgAT7uC10Y9I2SCN9FE"};
export const ROTATED_PUBLIC_JWK={"kty":"EC","x":"WWI973MIvGTyoNxc2VZxZUjIY9QaErC19zSg5en2Png","y":"RDJ6p6xz3hJ_e8VtBwqZrfQC75fQ7hOtNJnCj173Qg4","crv":"P-256"};
export const CLIENT_PUBLIC_KEYS=JSON.stringify([{id:CLIENT_ID,jwk:PUBLIC_JWK},{id:ROTATED_ID,jwk:ROTATED_PUBLIC_JWK}]);
export const FINGERPRINT_SECRET='TEST_ONLY_FINGERPRINT_0000000000000000000000000000';
