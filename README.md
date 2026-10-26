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
3. To integrate the SDK into your own app, follow the installation steps and the full function reference in [FUNCTIONS.md](FUNCTIONS.md).
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
