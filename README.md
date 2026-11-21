# payments-service

A small payment service any website can plug into. The customer pays on the provider's
**hosted checkout page** (Razorpay today), so card numbers, UPI IDs and bank details never touch
your servers, and this service stores **no customer data** at all: only an order reference, the
amount and the status.

Built with Java 17, Spring Boot 4, PostgreSQL and Flyway. One deployment per owner: you (or a
client) run your own copy with your own Razorpay keys, and it can serve several of your websites.

```
your website's server                      payments-service                         Razorpay
──────────────────────                     ─────────────────                        ────────
POST /v1/checkouts  ────────────────────▶  create checkout ─────────────────────▶  hosted page
   { amount, item, reference }             (idempotent)       ◀── checkout_url ───
redirect customer to checkout_url ─────────────────────────────────────────────▶  customer pays
                                            /v1/webhooks/razorpay ◀── signed webhook
                                            verify, apply once, update status
                                            outbox ──▶ POST your webhook "checkout.paid" (signed, retried)
customer lands on your success_url ◀──────  /v1/return/{id} ◀── customer redirected back
```

## What it does

- **Hosted checkout** through Razorpay Payment Links: UPI, cards, netbanking, wallets.
- **Idempotent create**: send an `Idempotency-Key`, and a retried request returns the original
  checkout instead of a second payment page. Reusing a key for a different request is refused (409).
- **Exactly-once webhook processing**: provider events are de-duplicated by event id in the same
  transaction as their effect, with the checkout row locked while it changes.
- **A status lifecycle where money only moves forward**: `created → paid → refunded / disputed`,
  `created → expired`. An expired checkout can't become paid; a duplicate event changes nothing.
- **Transactional outbox**: every status change and the webhook announcing it commit together. A
  dispatcher delivers it to your website, HMAC-signed, with exponential backoff (5 s up to 1 h).
  `FOR UPDATE SKIP LOCKED` plus a lease lets several instances share the queue safely.
- **Safe redirects**: success and cancel URLs must be on the site's allowed origins.
- **API keys stored as SHA-256 hashes** and compared in constant time.
- **Provider-neutral core**: Razorpay is one implementation of `PaymentProvider`; PayPal or Stripe
  slot in beside it.

## API

All requests from your website's **server** (never the browser) carry `Authorization: Bearer <api key>`.
Amounts are in the smallest currency unit: ₹999 is `99900` paise.

### Create a checkout

```bash
curl -X POST https://pay.example.com/v1/checkouts \
  -H "Authorization: Bearer $PAYMENTS_API_KEY" \
  -H "Idempotency-Key: order-1042" \
  -H "Content-Type: application/json" \
  -d '{
        "reference": "order-1042",
        "item": "30-min consultation",
        "amount_minor": 99900,
        "currency": "INR",
        "success_url": "https://example.com/thanks",
        "cancel_url": "https://example.com/pay/cancelled"
      }'
```

`201 Created` (or `200` when the same Idempotency-Key is replayed):

```json
{
  "id": "chk_4f9QzK8mN2pXr7Tb1cVw3d",
  "reference": "order-1042",
  "item": "30-min consultation",
  "amount_minor": 99900,
  "currency": "INR",
  "status": "created",
  "checkout_url": "https://rzp.io/i/AbC123",
  "expires_at": "2026-11-21T11:30:00Z",
  "created_at": "2026-11-21T11:00:00Z",
  "paid_at": null
}
```

Redirect the customer to `checkout_url`. After paying they come back to
`success_url?checkout_id=chk_...` (or `cancel_url?checkout_id=...`).

### Get a checkout

`GET /v1/checkouts/{id}` returns the same shape. Use it on your success page, but treat the
webhook below as the source of truth.

### Errors

Every error is `application/problem+json` with a stable `code`:

| Status | code | When |
| --- | --- | --- |
| 400 | `redirect_not_allowed` | success/cancel URL is not on the site's allowed origins |
| 400 | (validation) | missing fields, non-positive amount, bad currency |
| 401 | `invalid_api_key` | missing or wrong API key |
| 404 | `checkout_not_found` | no such checkout for this site |
| 409 | `idempotency_key_reused` | same Idempotency-Key, different request |
| 502 | `provider_error` | Razorpay unreachable or refused the request |

## Webhooks to your website

When a checkout changes status, your `webhook_url` receives:

```http
POST /api/payments/webhook
Payments-Event-Id: evt_7Hn2...
Payments-Event-Type: checkout.paid
Payments-Signature: t=1763722800,v1=5f2a...
Content-Type: application/json

{"id":"evt_7Hn2...","type":"checkout.paid","created_at":"2026-11-21T11:02:10Z",
 "data":{"id":"chk_4f9Q...","reference":"order-1042","amount_minor":99900,"currency":"INR","status":"paid", ...}}
```

Types: `checkout.paid`, `checkout.expired`, `checkout.refunded`, `checkout.disputed`.

**Verify every webhook** before trusting it: `v1` is `HMAC-SHA256(webhook_secret, t + "." + raw_body)`
in hex. Reject it if the signature doesn't match or `t` is more than 5 minutes old. Answer with any
2xx once stored; anything else is retried. The same event can arrive twice, so de-duplicate on
`Payments-Event-Id`.

Node / Next.js:

```ts
import crypto from "node:crypto";

export function verifyPaymentsWebhook(rawBody: string, header: string | null, secret: string): boolean {
  if (!header) return false;
  const parts = Object.fromEntries(header.split(",").map((p) => p.trim().split("=", 2)));
  const t = Number(parts.t);
  if (!t || Math.abs(Date.now() / 1000 - t) > 300 || !parts.v1) return false;
  const expected = crypto.createHmac("sha256", secret).update(`${t}.${rawBody}`).digest("hex");
  return expected.length === parts.v1.length &&
    crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(parts.v1));
}
```

Java: `WebhookSigner.verify(secret, header, rawBody, nowSeconds, 300)` in this repo does the same.

## Setting it up

### 1. Razorpay (Test Mode is free, no real money moves)

1. Create an account at razorpay.com and stay in **Test Mode**.
2. **Settings → API Keys → Generate Test Key** → `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`.
3. **Settings → Webhooks → Add**:
   - URL: `https://<this service>/v1/webhooks/razorpay`
   - Secret: a random string → `RAZORPAY_WEBHOOK_SECRET`
   - Events: `payment_link.paid`, `payment_link.expired`, `payment_link.cancelled`,
     `refund.processed`, `payment.dispute.created`

### 2. Register a website

```bash
API_KEY=$(openssl rand -hex 32)                     # give this to the website (server side only)
printf %s "$API_KEY" | sha256sum                    # -> PAYMENTS_SITES_0_API_KEY_SHA256
openssl rand -hex 32                                # -> PAYMENTS_SITES_0_WEBHOOK_SECRET (website gets it too)
```

Then set the `PAYMENTS_SITES_0_*` variables (see `.env.example`). Add more websites as
`PAYMENTS_SITES_1_*`, `PAYMENTS_SITES_2_*`, …

### 3. Run it

```bash
cp .env.example .env          # fill in the values above
docker compose up --build     # Postgres + the service on http://localhost:8080
```

Razorpay can't reach `localhost`, so test webhooks on a deployed copy (or through a tunnel).

### Deploy

It's a single Docker image (`Dockerfile`) that needs Postgres; any container host works. Set
`PUBLIC_BASE_URL` to the service's public address and `DATABASE_URL` as a JDBC URL, e.g. for Neon:
`jdbc:postgresql://ep-xxx-pooler.<region>.aws.neon.tech/neondb?sslmode=require` with
`DATABASE_USERNAME` and `DATABASE_PASSWORD`. Tables are created on startup (Flyway).
Health: `GET /actuator/health`.

## Development

```bash
./mvnw test      # unit tests + full-flow tests against a real Postgres (needs Docker)
```

The flow tests run the real service against Postgres in Testcontainers and stand-in servers for
Razorpay and a website, covering idempotency, signatures, exactly-once events, the outbox with
retries, and the customer's signed redirect, all without keys or network.

## Code map

| Path | What |
| --- | --- |
| `api/` | HTTP: checkouts, provider webhooks and returns, API-key filter, problem+json errors |
| `checkout/` | the checkout, its status lifecycle, persistence and creation rules |
| `events/` | applying provider events exactly once |
| `outbox/` | queuing, signing and delivering webhooks to websites |
| `provider/` | the provider interface and the Razorpay implementation |
| `site/` | registered websites, API-key check, allowed redirects |
| `db/migration/` | the schema |

## Roadmap

- Refunds through the API (`POST /v1/checkouts/{id}/refunds`)
- Pay buttons for static sites (products defined here, so a button can't change the price)
- PayPal and Stripe providers
- Admin view of checkouts and webhook deliveries
