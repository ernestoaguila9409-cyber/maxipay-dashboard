# Cloud Functions & Dashboard — Email Configuration

## 1. Receipt emails (Cloud Functions)

Callable `sendReceiptEmail` uses the SendGrid **HTTP API**. Configure on the Cloud Functions runtime:

| Variable | Purpose |
| --- | --- |
| `SENDGRID_API_KEY` | SendGrid API key (secret) |
| `SENDGRID_FROM_EMAIL` | Verified sender, e.g. `receipt@maxipaypos.com` |

```bash
firebase functions:secrets:set SENDGRID_API_KEY
# Set SENDGRID_FROM_EMAIL in Google Cloud Console → Cloud Functions → Environment variables.
```

## 2. Password-reset / account emails (Dashboard — `employee-access` route)

The dashboard **bypasses** Firebase Auth's built-in email templates so the email is **automatically branded** with the merchant's business name and logo from Firestore (`Settings/businessInfo`).

**How it works:**

1. `admin.auth().generatePasswordResetLink()` creates the one-time reset link.
2. Business name + logo are read from `Settings/businessInfo.businessName` / `logoUrl`.
3. A branded HTML email is composed and sent directly via the **SendGrid HTTP API**.

No one needs to touch the Firebase Console — the email subject, heading, and sign-off all use the current business name automatically.

**Required environment variables on the dashboard host (Vercel, etc.):**

| Variable | Purpose | Example |
| --- | --- | --- |
| `SENDGRID_API_KEY` | SendGrid API key (same key as Cloud Functions is fine) | `SG.xxxxx` |
| `SENDGRID_AUTH_FROM_EMAIL` *(optional)* | Sender address for auth emails; falls back to `SENDGRID_FROM_EMAIL`, then `noreply@maxipaypos.com` | `noreply@maxipaypos.com` |
| `SENDGRID_FROM_EMAIL` *(optional fallback)* | Used if `SENDGRID_AUTH_FROM_EMAIL` is not set | `receipt@maxipaypos.com` |
| `NEXT_PUBLIC_APP_URL` | Dashboard base URL (used as `continueUrl` after password reset) | `https://dashboard.maxipaypos.com` |

The **sender display name** in the inbox (e.g. *Acme Cafe* `<noreply@maxipaypos.com>`) is set dynamically from `Settings/businessInfo.businessName` — no static Firebase Console setting required.

**SendGrid domain:** `maxipaypos.com` domain authentication must be verified in SendGrid (SPF + DKIM). Any `@maxipaypos.com` address then works without a separate single-sender step.

## 3. Firebase Console SMTP (optional, for other flows)

Firebase Auth's built-in SMTP config (Authentication → Email templates → SMTP settings) is still useful as a **fallback** for any client-side `sendPasswordResetEmail()` or `sendEmailVerification()` calls that do not go through the dashboard route. If you configured it, keep it enabled. The dashboard `employee-access` route does **not** use it — it sends via SendGrid directly.

**SPF:** If you use Firebase's "custom domain" for auth *and* SendGrid for the same domain, combine both providers into a **single** SPF TXT record. See [Firebase custom domain docs](https://firebase.google.com/docs/auth/email-custom-domain).

## 4. Uber Eats Integration

Full Uber Eats API integration across five modules:

| File | Role |
| --- | --- |
| `uber.js` | Inbound webhook handler (+ auto-enriches orders via Get Order Details) |
| `uber-auth.js` | OAuth2 client-credentials token manager with in-memory caching |
| `uber-api.js` | Outbound REST client for all Uber Eats API endpoints |
| `uber-triggers.js` | Firestore trigger that syncs POS status changes to Uber |
| `uber-callables.js` | Callable Cloud Functions for menu sync, store config, and reporting |

### 4.1 Environment Secrets

```bash
firebase functions:secrets:set UBER_CLIENT_ID
firebase functions:secrets:set UBER_CLIENT_SECRET
```

| Variable | Purpose |
| --- | --- |
| `UBER_CLIENT_ID` | OAuth2 client ID (test: `e2pJSmPNTXYG6snNE-KAeRbz_hbRnC8_`) |
| `UBER_CLIENT_SECRET` | OAuth2 client secret + webhook signature validation |

### 4.2 Webhook Endpoint

```
https://us-central1-restaurantapp-180da.cloudfunctions.net/uberWebhook
```

Set this URL as the webhook in [developer.uber.com](https://developer.uber.com) for your test store.

| Event type | Action |
| --- | --- |
| `orders.notification` / `orders.created` | Create order in Firestore, fetch full details from Uber API |
| `orders.updated` | Update `uberStatus` field on order |
| `orders.cancel` / `orders.cancelled` / `orders.cancel.notification` | Set `status: "CANCELLED"` |

### 4.3 Uber API Endpoints (Outbound)

All outbound calls use OAuth2 Bearer tokens obtained via client-credentials flow.

| Uber Requirement | Uber API Endpoint | Triggered By |
| --- | --- | --- |
| Get Integration Details | `GET /v1/eats/stores/{id}/pos_data` | `uberGetStores` callable |
| Get Stores to User | `GET /v1/eats/stores` | `uberGetStores` callable |
| Update Item/Modifier | `PUT /v2/eats/stores/{id}/menus` | `uberSyncMenu` callable |
| Accept Order | `POST /v1/eats/orders/{id}/accept_pos_order` | Firestore trigger (status -> ACCEPTED) |
| Cancel Notification | Webhook handler | `uberWebhook` HTTP endpoint |
| Cancel Order | `POST /v1/eats/orders/{id}/cancel` | Firestore trigger (status -> CANCELLED) |
| Deny Order | `POST /v1/eats/orders/{id}/deny_pos_order` | Firestore trigger (status -> DENIED) |
| Get Order Details | `GET /v2/eats/order/{id}` | Auto-called after order webhook |
| Mark Order Ready | `POST /v1/eats/orders/{id}/ready` | Firestore trigger (status -> READY) |
| Get Report Files | `POST /v1/eats/report` | `uberGetReports` callable |

### 4.4 Firestore Trigger — Order Status Sync

When the POS updates an Uber order's status in Firestore, `uberOnOrderStatusChange` relays it to Uber:

| POS Status | Uber API Call |
| --- | --- |
| `ACCEPTED` | Accept Order |
| `DENIED` | Deny Order |
| `CANCELLED` | Cancel Order |
| `READY` | Mark Order Ready |

### 4.5 Firestore Structure

```
Orders/{uberOrderId}
  orderNumber: Long
  orderType: "UBER_EATS"
  orderSource: "uber_eats"
  status: "OPEN" | "ACCEPTED" | "READY" | "DENIED" | "CANCELLED"
  customerName: String
  totalInCents: Long
  employeeName: "Uber Eats"
  uberOrderId: String
  uberRawPayload: Object (webhook JSON)
  uberFullOrder: Object (full order from GET /v2/eats/order)
  createdAt / updatedAt: Timestamp

  items/{uber_item_N}
    name, quantity, basePriceInCents, unitPriceInCents, lineTotalInCents, modifiers[]
```

### 4.6 Cloud Functions Exported

| Export Name | Type | Description |
| --- | --- | --- |
| `uberWebhook` | HTTP (onRequest) | Inbound webhook from Uber |
| `uberOnOrderStatusChange` | Firestore trigger | Syncs POS status to Uber API |
| `uberGetStores` | Callable (onCall) | Get stores + integration details |
| `uberSyncMenu` | Callable (onCall) | Upload menu to Uber |
| `uberGetReports` | Callable (onCall) | Create / fetch reports |

### 4.7 Deploy

```bash
firebase deploy --only functions
```

### 4.8 Test Webhook (curl)

```bash
curl -X POST https://us-central1-restaurantapp-180da.cloudfunctions.net/uberWebhook \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "orders.notification",
    "event_id": "evt_test_001",
    "meta": { "resource_id": "test-order-001" },
    "order": {
      "id": "test-order-001",
      "eater": { "first_name": "John", "last_name": "Doe" },
      "total": 24.50,
      "items": [
        { "title": "Cheeseburger", "quantity": 2, "price": { "unit_price": 8.50 } },
        { "title": "Fries", "quantity": 1, "price": { "unit_price": 4.50 } }
      ]
    }
  }'
```
