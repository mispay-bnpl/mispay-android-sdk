# **MISPay Android SDK Documentation**

The MISPay Android SDK enables seamless integration of MIS Pay's Buy-Now-Pay-Later checkout into your Android application. This document provides detailed instructions for setting up the SDK, creating and starting checkouts, handling results, and troubleshooting errors.

---

## **Table of Contents**

1. [SDK Requirements](#sdk-requirements)
2. [Initialize the SDK](#initialize-the-sdk)
3. [Create and Start a Checkout](#create-and-start-a-checkout)
4. [Handle the Result](#handle-the-result)
5. [Track a Checkout](#track-a-checkout)
6. [Installation in Your App](#installation-in-your-app)
7. [Field Rules & Validation](#field-rules--validation)
8. [Lifecycle Guarantees](#lifecycle-guarantees)
9. [Error Codes](#error-codes)
10. [Legacy API (deprecated)](#legacy-api-deprecated)

---

## **1. SDK Requirements**

To integrate the MISPay SDK into your app, you need the following:

- **App ID (`x-app-id`)** and **App Secret (`x-app-secret`)**: credentials provided by the MIS Pay Technical Team.
- **Environment**: sandbox, production or a custom HTTPS base URL.

| Environment | Base URL |
| --- | --- |
| `MISPayEnvironment.SANDBOX` | `https://api-sandbox.mispay.dev/v1/api/` |
| `MISPayEnvironment.PRODUCTION` | `https://api.mispay.co/v1/api/` |
| `MISPayEnvironment.Custom(url)` | any HTTPS base URL (private deployment) |

### Example:
```kotlin
val environment = MISPayEnvironment.SANDBOX   // set PRODUCTION for live, Custom("https://…") for private deployments
```

The SDK has **no third-party dependencies** beyond AndroidX/Material listed in [Installation](#installation-in-your-app), minSdk 24 and compileSdk 34+.

---

## **2. Initialize the SDK**

Before using the SDK, initialize it once per app start (e.g. in your `Application` or first `Activity`). Calling it again replaces the configuration and drops any cached token.

```kotlin
MISPay.initialize(
    appId = "your-x-app-id",
    appSecret = "your-x-app-secret",
    environment = MISPayEnvironment.SANDBOX   // or MISPayEnvironment.PRODUCTION
)
```

Throws `IllegalArgumentException` when `appId` or `appSecret` is blank.

---

## **3. Create and Start a Checkout**

`MISPay.startCheckout` runs the full documented flow — token → decrypt → start-checkout → hosted checkout WebView:

```kotlin
MISPay.startCheckout(
    activity = this,
    request = request,
    onCheckoutStarted = { url, trackId ->     // optional — see "Track a Checkout"
        // checkout session created, checkout page about to open
    }
) { result ->
    when (result) {
        is MISPayResult.Success   -> showSuccess(result.token)
        is MISPayResult.Cancelled -> showCancelled()
        is MISPayResult.Error     -> showError(result.exception.meCode, result.exception.message)
    }
}
```

Throws `IllegalStateException` when `initialize` has not been called. Validation failures of `request` are **not** thrown — they are delivered as `MISPayResult.Error` on the main thread.

### **Example: Complete Checkout Request**

```kotlin
val request = CheckoutRequest(
    orderId = "1233-ABCD",                 // your unique order id (mandatory)
    totalPrice = "215",                    // mandatory, numeric string
    shippingAmount = "0",                  // mandatory, numeric string
    vat = "15",                            // mandatory, numeric string
    purchaseAmount = "200",                // totalPrice + shippingAmount + vat - discount
    purchaseCurrency = "SAR",              // mandatory
    lang = "en",                           // "en" or "ar"
    invoiceId = "1234567890",              // optional
    discount = Discount(                   // optional; omit when not applied
        percentage = "15",
        amount = "30",
        voucherName = "MIS-30"
    ),
    customerDetails = CustomerDetails(
        id = "customer-id",                // optional
        firstName = "First",               // optional
        lastName = "Last",                 // optional
        mobileNumber = "+966512345678",    // MANDATORY: 05XXXXXXXX, 9665XXXXXXXX or +9665XXXXXXXX
        email = "customer@example.com"     // optional
    ),
    orderDetails = OrderDetails(
        items = listOf(
            OrderItem(
                id = "OR12345",                        // optional
                sku = "1234568abcd",                   // optional
                nameArabic = "اسم المنتج",             // nameArabic or nameEnglish required
                nameEnglish = "Item name",
                quantity = "2",                        // mandatory, numeric string
                unitPrice = "100",                     // mandatory, numeric string
                itemUrl = "https://itemurl.com",       // optional
                imageUrl = "https://imageurl.com"      // optional
            )
        )
    ),
    shippingDetails = ShippingDetails(     // optional
        firstName = "First", lastName = "Last",
        countryCode = "966", city = "Riyadh", district = "Riyadh",
        line1 = "Address line 1", line2 = "Address line 2",
        gpsCoordinates = "41.40338, 2.17403"
    ),
    // Required when your MIS Pay callback URL setting is configured as "Dynamic"
    // (otherwise /start-checkout returns ME97):
    callbackUri = "https://your.server/mispay-callback"
)
```

---

## **4. Handle the Result**

The callback fires exactly once per `startCheckout` call, on the **main thread**:

| Result | Meaning |
| --- | --- |
| `MISPayResult.Success(token)` | Customer finished the MIS Pay flow. Verify `token` server-side — it encodes the final outcome of the payment. |
| `MISPayResult.Cancelled` | Customer backed out of the checkout pages. |
| `MISPayResult.Error(exception)` | Flow failed before or during the pages. `exception.meCode`, `exception.httpStatus` and `exception.message` carry the cause. |

```kotlin
MISPay.startCheckout(activity = this, request = request) { result ->
    when (result) {
        is MISPayResult.Success ->
            // Payment flow finished. Verify result.token server-side.
            showSuccess(result.token)
        is MISPayResult.Cancelled ->
            showCancelled()
        is MISPayResult.Error ->
            showError(result.exception.meCode, result.exception.message)
    }
}
```

`MISPayException.httpStatus` is `-1` when the failure did not produce an HTTP response (network / crypto / validation errors).

---

## **5. Track a Checkout**

`onCheckoutStarted` receives `(url, trackId)` once the checkout session is created. Use `trackId` to poll the authoritative status server-side:

```
GET /track-checkout/{trackId}
```

Statuses: `pending` / `in-progress` / `success` / `canceled` / `error`. This is also the authoritative fallback when your app process is killed during checkout (see [Lifecycle Guarantees](#lifecycle-guarantees)).

---

## **6. Installation in Your App**

### 1. Copy the AAR

Copy `mispaysdk.aar` into your app module: `app/libs/mispaysdk.aar`

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/mispaysdk.aar"))
}
```

### 2. Add the required dependencies

> ⚠️ **Important:** consuming an AAR via `files(...)` does **not** bring transitive dependencies with it. The SDK relies on the following libraries — declare them yourself (or make sure versions equal or higher are already present):

```kotlin
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")               // AppCompatActivity, back-dispatch
    implementation("com.google.android.material:material:1.10.0")      // Material3 theme used by the checkout activity
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // checkout WebView layout
}
```

### 3. Add permissions to your AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

### 4. Request location permission at runtime

The checkout pages may use geolocation. Request `ACCESS_FINE_LOCATION` before starting checkout (handling denial is up to your app):

```kotlin
if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0
    )
}
```

### 5. ProGuard / R8

Nothing to do. The AAR bundles consumer rules that keep the WebView JavaScript bridge and the public API intact under minification (`proguard.txt` inside the AAR).

---

## **7. Field Rules & Validation**

- All amounts/quantities are **numeric strings** (`"215"`, `"2"`) per the API contract.
- `purchaseAmount` must equal `totalPrice + shippingAmount + vat - discount.amount`;
  a mismatch is logged as a warning but never rejected (rounding is your call). The
  server validates the formula and rejects mismatches with **ME18**.
- `version` (`"v1.2"`) and `isMobile` (`true`) are set automatically.
- Invalid requests fail early with a descriptive message surfaced through
  `MISPayResult.Error` — nothing is sent to the API.
- `customerDetails.mobileNumber` is **mandatory** and must match `05XXXXXXXX`,
  `9665XXXXXXXX` or `+9665XXXXXXXX`.
- **Minimum amount:** the API rejects purchases below **SAR 145** (`ME13`).

---

## **8. Lifecycle Guarantees**

- **Exactly-once delivery** — the callback fires exactly once per `startCheckout`
  call, on the main thread: token received → `Success`; back navigation past the
  first checkout page → `Cancelled`; validation, network, API, decryption or
  checkout-page load failures → `Error`.
- **One checkout at a time** — starting a second checkout while one is in flight
  immediately returns `Error` ("checkout already in progress") instead of
  mis-delivering the first checkout's result.
- **Page load failures are surfaced** — if the checkout URL fails to load
  (offline, DNS, HTTP 4xx/5xx on the main document), you receive `Error`, not a
  white screen.
- **Process death** — if Android kills your app while the customer is on the MIS
  Pay pages, no in-app result can be delivered. The authoritative fallbacks are
  server-side: your `callbackUri` webhook (MIS Pay POSTs the encrypted final
  status there) and `GET /track-checkout/{trackId}` polling with the `trackId`
  from `onCheckoutStarted`.

---

## **9. Error Codes**

Documented by MIS Pay (`doc.mispay.co`):

| Code | Meaning |
| --- | --- |
| ME01 / ME02 | App ID / App Secret not provided |
| ME03 | Merchant application not found (wrong App ID or App Secret) |
| ME04 | Checkout session is invalid |
| ME05 | Transaction not found |
| ME10 | Token not valid (SDK auto-invalidates cache and retries once) |
| ME11 | Session expired (SDK auto-invalidates cache and retries once) |
| ME13 | Amount below the minimum (SAR 145) |
| ME18 | `purchaseAmount` does not match `totalPrice + shippingAmount + vat - discount.amount` (server-validated) |
| ME97 | Invalid request — most commonly a missing `callbackUri` when your account's callback URL is configured as *Dynamic* |
| E9997 | Server-side error (report the `x-trace-id` response header to MIS Pay support) |

---

## **10. Legacy API (deprecated)**

`MISPay.checkout(context, url) { token -> }` from SDK 1.x still works unchanged
(opens a raw checkout URL, token-only callback, no Cancelled/Error delivery) but is
deprecated — migrate to `initialize` + `startCheckout` for typed results and the
full token → start-checkout flow.

---

## Support

For questions or issues, contact **MIS Pay Integration Support** at [partners-support@mispay.co](mailto:partners-support@mispay.co). When reporting issues, include the `x-trace-id` response header if you captured it.
