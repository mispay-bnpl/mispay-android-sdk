# **MISPay Android SDK Example App**

This repository provides a working example of how to integrate the **MISPay Android SDK** into an Android application. The example demonstrates key functionalities and serves as a reference for developers using the MISPay SDK.

The SDK handles the complete documented MIS Pay Buy-Now-Pay-Later checkout flow:

```
MISPay.initialize(appId, appSecret, environment)          once per app start
MISPay.startCheckout(activity, request) { result -> }     per purchase
        │
        ├─ 1. GET /token            (x-app-id + x-app-secret headers)
        ├─ 2. Decrypt token         (AES-256-GCM, PBKDF2-HMAC-SHA256, 40 000 iterations — automatic)
        ├─ 3. Cache JWT             (valid ~8 hours, auto-refresh on expiry or ME10/ME11)
        ├─ 4. POST /start-checkout  (Bearer JWT + full order body; one automatic retry on ME10/ME11)
        └─ 5. Open result.url       in the MISPay WebView (hosted MIS Pay checkout pages)
                │
                ├─ customer completes  →  MISPayResult.Success(token)
                ├─ customer backs out  →  MISPayResult.Cancelled
                └─ any step fails      →  MISPayResult.Error(MISPayException)
```

The callback is always invoked on the **main thread**, exactly **once** per call.

---

## **Features**

- Example integration of the MISPay Android SDK (`mispaysdk` module + runnable demo app).
- Demonstrates the complete payment flow: token → start-checkout → hosted checkout WebView.
- Configurable environment for testing (`SANDBOX`, `PRODUCTION` or a custom HTTPS base URL).
- Typed results with MIS Pay error codes (`ME01`–`ME97`, `E9997`) surfaced to your app.

---

## **Requirements**

- **Android Studio**: Ladybug (2024.1.1) or higher.
- **Minimum Android SDK Version**: 24 (Android 7.0 Nougat).
- **Target / Compile SDK Version**: 34.
- **Credentials** (`x-app-id`, `x-app-secret`) provided by the MIS Pay Technical Team.

---

## **Setup Instructions**

### **1. Download the Example App**

- Download the project as a ZIP file from the repository, or clone it:

```bash
git clone <this repository URL>
```

- Extract the ZIP file (if downloaded) to a local directory.

### **2. Open the Project in Android Studio**

- Launch Android Studio.
- Open the project directory (`mispay-android-sdk`).
- Let Gradle sync automatically.

### **3. Configure Your SDK Credentials**

- Copy the credential template and fill in your sandbox credentials from the MIS Pay Technical Team:

```bash
cp app/credentials.properties.example app/credentials.properties
```

- Edit `app/credentials.properties` (gitignored — never commit it):

```properties
MISPAY_APP_ID=REPLACE_ME      # your sandbox x-app-id
MISPAY_APP_SECRET=REPLACE_ME  # your sandbox x-app-secret
```

The values are injected into `BuildConfig` at build time; without this file the demo still runs and surfaces `ME03` (merchant application not found).

### **4. Verify MISPay SDK Version**

The demo app uses the SDK from the source module in this repository:

```kotlin
implementation(project(":mispaysdk"))
```

If you integrate into your own app, you consume the distributable AAR — copy `mispaysdk.aar` into `app/libs/` and declare it:

```kotlin
implementation(files("libs/mispaysdk.aar"))
```

> **Note:** Ensure you use the latest AAR provided by the MIS Pay team, and check [FUNCTIONS.md](FUNCTIONS.md) for the required transitive dependencies (`appcompat`, `material`, `constraintlayout`).

### **5. Build and Run the App**

- Connect a physical Android device or start an emulator.
- Build and install:

```bash
./gradlew installDebug
```

- Tap **Pay with MISPay** in the demo to start a sandbox checkout.

---

## **Integrate the SDK Into Your Existing App**

This section is for merchants adding the SDK to their **own** application. To try the flow first, run the example app above.

**Prerequisites**

- Android Studio Ladybug (2024.1.1) or higher.
- An app with `minSdk 24` or higher (`compileSdk 34` recommended).
- The latest `mispaysdk.aar` and your credentials (`x-app-id`, `x-app-secret`) from the MIS Pay Technical Team.

### **1. Add the AAR and Its Dependencies**

Copy the AAR into your app module and declare it in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/mispaysdk.aar"))

    // Required by the SDK. A local AAR carries no dependency metadata,
    // so these are NOT pulled in automatically — declare them yourself
    // (skip any your app already has at these versions or newer):
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
```

> **Warning:** if `appcompat` (or the others) is missing, your app builds fine but **crashes with `NoClassDefFoundError` the moment the checkout screen opens**. If you see that crash, come back to this step.

### **2. Manifest — Nothing to Add**

The AAR's manifest is merged into your app automatically. It already declares the `INTERNET` and location permissions and the SDK's checkout activity (with its own theme) — **do not** add them again.

Optional: the checkout WebView auto-grants geolocation prompts. If you want the customer's location prefilled, request `ACCESS_FINE_LOCATION` at runtime like the demo does (see `LibraryDemoActivity`); checkout works without it.

### **3. Initialize the SDK**

Once per app start, before the first checkout — e.g. in your `Application`:

```kotlin
import com.finbyte.mispaysdk.MISPay
import com.finbyte.mispaysdk.MISPayEnvironment

override fun onCreate() {
    super.onCreate()
    MISPay.initialize(
        appId = "YOUR_X_APP_ID",      // from the MIS Pay Technical Team
        appSecret = "YOUR_X_APP_SECRET",
        environment = MISPayEnvironment.SANDBOX   // or PRODUCTION, or Custom("https://…")
    )
}
```

`MISPayEnvironment.Custom(baseUrl)` requires `https://`. Keep the secret out of version control and logs.

### **4. Build the Order and Start Checkout**

```kotlin
import com.finbyte.mispaysdk.MISPay
import com.finbyte.mispaysdk.MISPayResult
import com.finbyte.mispaysdk.model.*

val request = CheckoutRequest(
    orderId = "ORDER-${System.currentTimeMillis()}",   // unique per order
    totalPrice = "215",          // all amounts are numeric STRINGS, by API contract
    shippingAmount = "0",
    vat = "15",
    purchaseAmount = "200",      // must equal totalPrice + shippingAmount + vat − discount.amount
    purchaseCurrency = "SAR",
    lang = "en",                 // "en" or "ar"
    discount = Discount(percentage = "15", amount = "30", voucherName = "MIS-30"), // optional
    customerDetails = CustomerDetails(
        mobileNumber = "+966520006780",   // mandatory: 05…, 9665… or +9665… format
        firstName = "First", lastName = "Last", email = "customer@example.com"
    ),
    orderDetails = OrderDetails(
        items = listOf(
            OrderItem(
                quantity = "2", unitPrice = "100",          // mandatory, numeric strings
                nameEnglish = "Item name",                   // nameArabic or nameEnglish required
                id = "OR12345", sku = "1234568abcd",
                itemUrl = "https://itemurl.com", imageUrl = "https://imageurl.com"
            )
        )
    ),
    shippingDetails = ShippingDetails(city = "riyadh", district = "riyadh", line1 = "street"), // optional
    callbackUri = "https://example.com/mispay-callback"  // required when your MIS Pay callback setting is "Dynamic"
)

MISPay.startCheckout(
    activity = this,
    request = request,
    onCheckoutStarted = { url, trackId ->
        // Checkout page opened — persist trackId if you want to poll status server-side.
    }
) { result ->
    when (result) {
        is MISPayResult.Success  -> verifyTokenServerSide(result.token)  // see step 5
        is MISPayResult.Cancelled -> show("Customer left the checkout")
        is MISPayResult.Error     -> show("Error ${result.exception.meCode ?: ""}: ${result.exception.message}")
    }
}
```

Guarantees: the callback runs on the **main thread**, exactly **once** per call. Only one checkout can be active at a time — a second call fails fast with `MISPayResult.Error`. Request-validation problems are delivered to the callback as `Error` (never thrown); the only thrown exception is `IllegalStateException` if you skipped `MISPay.initialize`.

> **Amount formula:** keep `totalPrice + shippingAmount + vat − discount.amount = purchaseAmount` exact — the SDK logs a warning on mismatch and the MIS Pay API **rejects** mismatches with `ME18`.

ProGuard/R8 needs no extra keep rules — consumer rules ship inside the AAR.

### **5. Verify the Token Server-Side**

`MISPayResult.Success(token)` means the customer finished the MIS Pay pages — the token encodes the payment outcome and **must be verified by your backend** (see [doc.mispay.co](https://doc.mispay.co)). Authoritative fallbacks that work even if your app process is killed: your `callbackUri` webhook and `GET /track-checkout/{trackId}` polling with the `trackId` from `onCheckoutStarted`.

### **6. Troubleshooting**

| Symptom / code | Meaning and fix |
| --- | --- |
| `NoClassDefFoundError` on checkout open | Missing transitive dependency — see step 1. |
| `ME01` | Credentials rejected — check `appId`/`appSecret`. |
| `ME03` | Merchant application not found — wrong `appId` or environment. |
| `ME04` | Session cache missing — call `MISPay.initialize` before `startCheckout`. |
| `ME10` / `ME11` | Authorization token invalid/expired — the SDK refreshes and retries once automatically; persistent failures mean credentials/environment mismatch. |
| `ME18` | Order amounts don't satisfy the purchase-amount formula — fix the amounts. |
| `ME97` | `callbackUri` missing — required for accounts with a Dynamic callback setting. |
| `E9997` | Unexpected MIS Pay server error — retry, and report with the `x-trace-id` header if captured. |

**Next steps:** full function reference in [FUNCTIONS.md](FUNCTIONS.md) · official docs: [doc.mispay.co](https://doc.mispay.co)

---

## **Repository Structure**

```
├── README.md                       # Documentation file
├── FUNCTIONS.md                    # Full SDK function reference
├── app/                            # Demo application module
│   ├── src/                        # Demo source (LibraryDemoActivity.kt)
│   ├── credentials.properties      # Your sandbox credentials (gitignored)
│   └── credentials.properties.example  # Template to copy
├── mispaysdk/                      # The SDK source module
│   └── src/main/java/com/finbyte/mispaysdk/
│       ├── MISPay.kt               # Public entry point
│       ├── MISPayActivity.kt       # Checkout WebView activity
│       ├── MISPayResult.kt         # Success / Cancelled / Error
│       ├── MISPayException.kt      # Typed error (meCode, httpStatus)
│       ├── MISPayEnvironment.kt    # SANDBOX / PRODUCTION / Custom
│       ├── crypto/                 # AES-256-GCM token decryption
│       ├── network/                # /token and /start-checkout client
│       ├── token/                  # JWT cache (~8 h)
│       └── model/                  # CheckoutRequest + nested models
├── mispaysdk.aar                   # Distributable AAR
├── build.gradle.kts                # Project-level Gradle configuration
├── settings.gradle.kts             # Module wiring
└── gradlew                         # Gradle wrapper script
```

---

## **Usage Instructions**

1. Run the demo app and tap **Pay with MISPay** to see the full checkout flow (token → start-checkout → hosted checkout pages → typed result).
2. Refer to `app/src/main/java/com/finbyte/mispay_android_sdk/LibraryDemoActivity.kt` for implementation details of the MISPay SDK — it initializes the SDK, builds the docs sample order and handles every result type.
3. To integrate the SDK into your own app, follow [Integrate the SDK Into Your Existing App](#integrate-the-sdk-into-your-existing-app) above and the full function reference in [FUNCTIONS.md](FUNCTIONS.md).
4. To rebuild the distributable AAR from source:

```bash
./gradlew :mispaysdk:assembleRelease
# → mispaysdk/build/outputs/aar/mispaysdk-release.aar (copy to mispaysdk.aar for distribution)
./gradlew :mispaysdk:test          # unit tests
```

---

## **Important Notes**

- **Do not hardcode sensitive credentials** (e.g., `MISPAY_APP_ID`, `MISPAY_APP_SECRET`) in production apps. This example reads them from a gitignored properties file for testing purposes only. Use secure methods, such as encrypted storage or server-side configurations, for production environments.
- `app/credentials.properties` is **gitignored** — never commit credentials.
- **You can check all the supported functions here: [Functions](FUNCTIONS.md)**

---

## **Support**

For issues or questions about the MISPay SDK, contact **MIS Pay Technical Team / Integration Support** at [partners-support@mispay.co](mailto:partners-support@mispay.co)

- Official docs: <https://doc.mispay.co>
- When reporting issues, include the `x-trace-id` response header if you captured it.
