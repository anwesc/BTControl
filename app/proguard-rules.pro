# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Shizuku 服务/代理类是用字符串反射拿的（Class.forName("moe.shizuku.server.IShizukuService$Stub")），
# 名字不参与编译期引用，R8 会整个删掉，导致 release 包静默安装/关联/连 HFP 全部失效。
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-dontwarn moe.shizuku.**
