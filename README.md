# mispay-android-sdk



## Getting started

This documentation explains the basic steps to implement the MISPay SDK to Android applications.

## Setup SDK

Copy aar file to your app libs folder: app-> libs->aars->mispaysdk.aar

In app level build.gradle.kts, add mispaysdk.aar inside your dependencies

```
dependencies { 
implementation(files("libs/mispaysdk.aar"))
implementation("com.google.android.material:material:1.11.0")
}
```


In your settings.gradle.kts add this code to your repositories:

```
repositories {
        flatDir {
            dirs("libs")
        }
        google()
        mavenCentral()
    }
```
Sync your gradle. Please note that if you are using Groovy instead of Kotlin DSL, syntax might be slightly different.

## Add Necessary Permissions to your Android Manifest

This library requires internet and location permissions to operate. Add these permissions if you do not have them already.

```
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

## Request Location permission from your users

You need to get location permission from the user before running any library related code. Implementation is up to you, but here's a sample code to ask permissions:

```
if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 0)
}
```

Please note that this sample code only asks for location permission, it does not handle the whole flow i.e what happens if the user rejects the permission. Make sure to implement all necessary steps to inform the user.

## Use the SDK

You can use the SDK by providing Activity context and the target url, it will automatically open the URL in a webview and return to the app when the payment process is over with a token indicating the success or the failure of the payment.

```
MISPay.checkout(this, TARGET_URL){ token ->
        //Handle the received token here
    }
```

## Integration
This SDK is to allow MIS-Pay merchants seamlessly integrate with MIS-Pay payment gateway.

A detailed integration documentation be found [here](https://cdn.mispay.co/common/documents/Integration_Guide.pdf).

Also if you are working on a different platform, you can find the SDKs for other platforms [here](https://github.com/mispay-bnpl?tab=repositories).