# JS 桥：addJavascriptInterface 暴露的方法不能混淆
-keepclassmembers class com.aiyoyo.dshlauncher.WebActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
