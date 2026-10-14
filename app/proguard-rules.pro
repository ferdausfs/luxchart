# LuxChart release keep rules.
# The app is a thin WebView shell; R8 must never break WebView callbacks
# (current or future JS bridges / clients).

-keepattributes JavascriptInterface

# Members of WebView callback classes are invoked from Chromium's native
# layer, not from Java, so R8 may otherwise strip "unused" overrides.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
    protected *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
    protected *;
}

# JavaScript bridge methods are invoked reflectively from JS — never strip.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.ferdausfs.luxchart.MainActivity$ErrorPageBridge { *; }
-keep class com.ferdausfs.luxchart.MainActivity$BlobBridge { *; }
