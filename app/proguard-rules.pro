# kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebView JS 桥：注册名与方法名由网页直接调用，不能混淆
-keep class com.ugport.web.WebBridge { *; }
-keep class com.ugport.web.WebMediaBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
