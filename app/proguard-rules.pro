# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
-keepclassmembers class com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment$WebViewJavaScriptInterface {
   public *;
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontwarn org.immutables.value.Value$Default
-dontwarn org.immutables.value.Value$Immutable
-dontwarn org.immutables.value.Value$Style$BuilderVisibility
-dontwarn org.immutables.value.Value$Style$ImplementationVisibility
-dontwarn org.immutables.value.Value$Style

-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.liulishuo.okdownload.** { *; }
-keep class io.lindstrom.** { *; }
-keep class org.immutables.** { *; }