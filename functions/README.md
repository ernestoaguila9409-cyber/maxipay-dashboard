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

## 4. Menu item images (Pexels) — Android + parity with dashboard

Callable functions `menuItemImageSearch` and `menuItemImageCommitPexels` power **Inventory → Item detail → Find image (Pexels)** on the Android app, using the same flow as the dashboard: Pexels preview → download from `images.pexels.com` → upload to **Firebase Storage** → store the resulting **HTTPS download URL** on `MenuItems.imageUrl` (never the raw Pexels URL).

Set these on the **Cloud Functions** runtime (same values as dashboard `.env` where applicable):

| Variable | Purpose |
| --- | --- |
| `PEXELS_API_KEY` | Required. Pexels API key ([pexels.com/api](https://www.pexels.com/api/)). |
| `OPENAI_API_KEY` | Optional. If set, refines the search query from the item name (same behavior as `/api/menu/item-image-search`). If unset, the item name is searched on Pexels directly. |
| `OPENAI_ITEM_IMAGE_MODEL` | Optional. Defaults to `gpt-4o-mini`. |

```bash
# Example: set secret then bind in Cloud Console, or use Environment variables in GCP.
firebase functions:secrets:set PEXELS_API_KEY
```

Both callables are deployed in **`us-central1`** (must match the Android client, which calls that region). If the app shows **NOT_FOUND**, redeploy those functions.

Because `firebase.json` names this codebase **`default`**, include it in `--only` filters (otherwise the CLI reports *No function matches given --only filters*):

```bash
firebase deploy --only "functions:default:menuItemImageSearch,functions:default:menuItemImageCommitPexels"
```

PowerShell: keep the **quotes** so the comma is not treated as a separator.

To deploy every function in the `functions/` folder:

```bash
firebase deploy --only functions
```

**`UNAUTHENTICATED` from the Android app** usually means either (1) the Cloud Run service behind the callable does not allow the client to reach the endpoint (redeploy after pulling the repo so `invoker: "public"` is applied), or (2) the app has no valid Firebase Auth user / ID token—enable **Anonymous** sign-in in Firebase Console → Authentication → Sign-in method, then sign out and back into the POS. The Android client refreshes the ID token before each call.

## 5. Uber Eats Integration

Full Uber Eats API integration across five modules:

| File | Role |
| --- | --- |
| `uber.js` | Inbound webhook handler (+ auto-enriches orders via Get Order Details) |
| `uber-auth.js` | OAuth2 client-credentials token manager with in-memory caching |
| `uber-api.js` | Outbound REST client for all Uber Eats API endpoints |
| `uber-triggers.js` | Firestore trigger that syncs POS status changes to Uber |
| `uber-callables.js` | Callable Cloud Functions for menu sync, store config, and reporting |

### 5.1 Environment Secrets

```bash
firebase functions:secrets:set UBER_CLIENT_ID
firebase functions:secrets:set UBER_CLIENT_SECRET
```

| Variable | Purpose |
| --- | --- |
| `UBER_CLIENT_ID` | OAuth2 client ID (test: `e2pJSmPNTXYG6snNE-KAeRbz_hbRnC8_`) |
| `UBER_CLIENT_SECRET` | OAuth2 client secret + webhook signature validation |

### 5.2 Webhook Endpoint

```
https://us-central1-restaurantapp-180da.cloudfunctions.net/uberWebhook
```

Set this URL as the webhook in [developer.uber.com](https://developer.uber.com) for your test store.

| Event type | Action |
| --- | --- |
| `orders.notification` / `orders.created` | Create order in Firestore, fetch full details via Get Order Details |
| `orders.scheduled.notification` | Create order + tag `scheduled: true` and `scheduledFor` |
| `orders.updated` | Update `uberStatus` field on order |
| `orders.cancel` / `orders.cancelled` / `orders.cancel.notification` | Set `status: "CANCELLED"` |
| `orders.cancel.failure` | Persist `cancelFailure` on order doc (POS surfaces retry) |
| `orders.release.failure` | Persist `releaseFailure` on order doc |
| `orders.fulfillment_issue.resolution` | Persist `fulfillmentIssue` payload on order doc |

### 5.3 Uber API Endpoints (Outbound)

All outbound calls use OAuth2 Bearer tokens obtained via client-credentials flow. The Order Suite uses `/v1/delivery/order/...`; integration / menu uses `/v1/eats/stores/...` and `/v2/eats/stores/...`.

| Uber Requirement | Uber API Endpoint | Triggered By |
| --- | --- | --- |
| Activate Integration | `POST /v1/eats/stores/{id}/pos_data` | `uberActivateIntegration` callable |
| Update Integration Details | `PATCH /v1/eats/stores/{id}/pos_data` | (internal helper) |
| Get Integration Details | `GET /v1/eats/stores/{id}/pos_data` | `uberGetStores` callable |
| Get Stores to User | `GET /v1/eats/stores` | `uberGetStores` callable |
| Upload / Replace Menu | `PUT /v2/eats/stores/{id}/menus` | `uberSyncMenu` callable |
| Update Item | `POST /v2/eats/stores/{id}/menus/items/{item_id}` | `uberUpdateItem` callable |
| Update Modifier Group | `POST /v2/eats/stores/{id}/menus/modifier_groups/{id}` | `uberUpdateModifier` callable |
| Suspend / Unsuspend Item (86) | `POST /v2/eats/stores/{id}/menus/items/{id}` with `suspension_info` | `uberSuspendItem` callable |
| Accept Order | `POST /v1/delivery/order/{id}/accept` | Firestore trigger (status → `ACCEPTED`) |
| Deny Order | `POST /v1/delivery/order/{id}/deny` | Firestore trigger (status → `DENIED`) |
| Cancel Order | `POST /v1/delivery/order/{id}/cancel` | Firestore trigger (status → `CANCELLED`) |
| Mark Order Ready | `POST /v1/delivery/order/{id}/ready` | Firestore trigger (status → `READY`) |
| Adjust Order | `POST /v1/delivery/order/{id}/adjust` | Firestore trigger (status → `ADJUSTED`) or `uberAdjustOrder` |
| Release Order | `POST /v1/delivery/order/{id}/release` | Firestore trigger (status → `RELEASED`) or `uberReleaseOrder` |
| Resolve Fulfillment Issue | `POST /v1/delivery/order/{id}/resolve_fulfillment_issue` | `uberResolveFulfillmentIssue` callable |
| Get Order Details | `GET /v1/delivery/order/{id}` | Auto-called after order webhook |
| Get Report Files | `POST /v1/eats/report` | `uberGetReports` callable |

### 5.4 Firestore Trigger — Order Status Sync

When the POS updates an Uber order's status in Firestore, `uberOnOrderStatusChange` relays it to Uber:

| POS Status | Uber API Call | Required fields on Order doc |
| --- | --- | --- |
| `ACCEPTED` | Accept Order | `orderNumber`, `employeeName` |
| `DENIED` | Deny Order | `denyReason` (optional) |
| `CANCELLED` | Cancel Order | `cancelReason`, `cancelDetails` (optional) |
| `READY` | Mark Order Ready | — |
| `ADJUSTED` | Adjust Order | `uberAdjustment` (full payload) or `adjustReason` |
| `RELEASED` | Release Order | `releaseReason`, `releaseDetails` (optional) |

### 5.5 Firestore Structure

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

### 5.6 Cloud Functions Exported

| Export Name | Type | Description |
| --- | --- | --- |
| `uberWebhook` | HTTP (onRequest) | Inbound webhook from Uber |
| `uberOnOrderStatusChange` | Firestore trigger | Syncs POS status to Uber API |
| `uberEnrichNewOrder` | Firestore trigger | Fetches full order details after webhook persists order |
| `uberGetStores` | Callable | Get stores + integration details |
| `uberActivateIntegration` | Callable | Activate POS integration on a store |
| `uberSyncMenu` | Callable | Upload / replace full menu |
| `uberUpdateItem` | Callable | Update a single menu item (price, title, etc.) |
| `uberUpdateModifier` | Callable | Update a single modifier group |
| `uberSuspendItem` | Callable | 86 / unsuspend a menu item |
| `uberAdjustOrder` | Callable | Adjust a live order (OOS, modifier swap) |
| `uberReleaseOrder` | Callable | Release order back to Uber |
| `uberResolveFulfillmentIssue` | Callable | Resolve a fulfillment issue |
| `uberRunCertificationTests` | Callable | Run the full Uber certification test sequence |
| `uberGetReports` | Callable | Create / fetch reports |

### 5.7 Deploy

```bash
firebase deploy --only functions
```

### 5.8 Uber Certification Test Sequence

Uber's onboarding team requires that the following endpoints all return success against a sandbox store. Run the harness once per store to capture the entire log trail in one place:

```bash
firebase functions:shell
> uberRunCertificationTests({ storeId: "5e3578ad-cddd-4f48-bfd9-8ea2c2119837", itemId: "item_placeholder" })
```

The callable returns one entry per step (`ok`, `elapsedMs`, `data` or `error`). Steps exercised, in order:

1. `getStores` — `GET /v1/eats/stores`
2. `activateIntegration` — `POST /v1/eats/stores/{id}/pos_data`
3. `getIntegrationDetails` — `GET /v1/eats/stores/{id}/pos_data`
4. `uploadMenu` — `PUT /v2/eats/stores/{id}/menus`
5. `updateMenuItem` — `POST /v2/eats/stores/{id}/menus/items/{item_id}`
6. `updateModifierGroup` (optional, if `modifierGroupId` passed)
7. `suspendItem(60min)` — `POST .../menus/items/{id}/suspend_info`
8. `unsuspendItem` — same endpoint with `suspend_until: 0`

For the order suite (Accept / Deny / Cancel / Ready / Adjust / Release), trigger each by changing the order's `status` in Firestore (or via the dashboard). The corresponding Uber API call is logged in `[uber-triggers]` and verified via `getOrderDetails` immediately after.

### 5.9 Test Webhook (curl)

Order created:
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

Scheduled order:
```bash
curl -X POST https://us-central1-restaurantapp-180da.cloudfunctions.net/uberWebhook \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "orders.scheduled.notification",
    "event_id": "evt_test_sched_001",
    "meta": { "resource_id": "sched-order-001" },
    "order": {
      "id": "sched-order-001",
      "eater": { "first_name": "Jane", "last_name": "Smith" },
      "estimated_ready_for_pickup_at": "2026-04-30T18:00:00Z",
      "total": 19.00,
      "items": [{ "title": "Pizza", "quantity": 1, "price": { "unit_price": 19.00 } }]
    }
  }'
```

Fulfillment issue resolution:
```bash
curl -X POST https://us-central1-restaurantapp-180da.cloudfunctions.net/uberWebhook \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "orders.fulfillment_issue.resolution",
    "event_id": "evt_test_fi_001",
    "meta": { "resource_id": "test-order-001" },
    "order": { "id": "test-order-001" },
    "fulfillment_issue": { "type": "OUT_OF_ITEM", "resolution": "REFUND" }
  }'
```
