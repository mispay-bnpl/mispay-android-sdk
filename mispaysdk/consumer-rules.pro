# MISPay Android SDK — consumer rules (bundled into the AAR as proguard.txt).
# These apply automatically when a merchant app enables R8/ProGuard minification.

# The MIS Pay checkout page calls window.Android.onTokenReceived(token) from
# JavaScript. WebView dispatches this reflectively by method name, so R8 must
# never rename or strip the bridge methods.
-keepclassmembers class com.finbyte.mispaysdk.JavaScriptInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the public SDK surface intact so merchant code compiles identically
# against minified apps and SDK stack traces stay readable.
-keep public class com.finbyte.mispaysdk.MISPay { public *; }
-keep public class com.finbyte.mispaysdk.MISPayActivity { public *; }
-keep public class com.finbyte.mispaysdk.MISPayResult { *; }
-keep public class com.finbyte.mispaysdk.MISPayResult$* { *; }
-keep public class com.finbyte.mispaysdk.MISPayException { public *; }
-keep public class com.finbyte.mispaysdk.MISPayEnvironment { *; }
-keep public class com.finbyte.mispaysdk.model.** { *; }
-keep public interface com.finbyte.mispaysdk.TokenCallback { *; }

# Preserve source file names and line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
