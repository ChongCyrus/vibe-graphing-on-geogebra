# Keep GeoGebra-related JavascriptInterface methods (the bridge class is kept by default
# because it is referenced directly; this rule is a safety net).
-keepclasseswithmembers class com.ggb.classic5.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
