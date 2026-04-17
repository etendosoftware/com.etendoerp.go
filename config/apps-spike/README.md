# Apps Spike keys

RSA 2048 keypair used by the F1 spike JWT issuer (ETP-3805).

## Files

- `public-key.pem` — tracked; published through JWKS and used by external apps to verify JWTs
- `private-key.pem` — **gitignored**; used by `JwtIssuerService` to sign JWTs

## Regenerate

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private-key.pem
openssl rsa -pubout -in private-key.pem -out public-key.pem
```

## Production

Not for production. F1 production rollout must load the private key from a secrets manager
(AWS Secrets Manager, HashiCorp Vault, etc.) and rotate via the `kid` header, not from the
filesystem.
