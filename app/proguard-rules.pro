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

# If a JS interface is ever added (future feature), keep annotated members.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
