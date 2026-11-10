# ============================================================================
# GcmodAi R8 规则
#
# 目标：在「不改变任何用户可见行为」的前提下，把 dex 里可省的部分省掉。
# 每一条都写清为什么需要，改动前请先读注释，别整段删。
# ============================================================================

# 1) 全量优化：5 轮迭代让 R8 有足够机会做内联/常量折叠/去虚拟化
-optimizationpasses 5

# 2) 类名统一收到根包。R8 会把 com.gcmodai.app.ui.pages.chat.ChatVM$Companion
#    这类长名压成 a.b，dex 常量池里的类型描述符随之变短，是纯收益的瘦身项。
#    adaptclassstrings 必须跟着开：类名改了以后，代码里以字符串形式出现的类名
#    （Class.forName 之类）要同步改写，否则反射会找不到类。
-repackageclasses ''
-allowaccessmodification
-adaptclassstrings

# 3) 崩溃栈要能看：保留行号，源文件名统一成 SourceFile（不泄露真实文件名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 4) 注解/泛型签名/内部类信息：kotlinx.serialization、Koin、Room 都依赖
#    这里每多留一类属性，都要在全部类上多存一份数据，所以只留真正需要的；
#    Exceptions / EnclosingMethod 对运行时没有价值，不留
-keepattributes *Annotation*, InnerClasses, Signature

# ---------------------------------------------------------------------------
# kotlinx.serialization
# 生成的 serializer 通过 @Serializable 类的 companion 反射取，混淆掉就全盘解析失败
# ---------------------------------------------------------------------------
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** serializer(...);
}

# ---------------------------------------------------------------------------
# WebView JS 桥
# Mermaid.kt 里的 @JavascriptInterface 方法一旦被改名，图表渲染会静默失败
# （不报错，只是永远空白），所以必须按注解保留
# ---------------------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------
# JSch
# SSH 的算法实现（cipher / kex / mac / zlib）是按配置字符串用 Class.forName 实例化的，
# 整包 keep 会把用不到的算法全留下；只保这些实现子包即可，别 keep 整个 jsch。
# BouncyCastle 只在 PEM / 证书解析路径出现，keep 它的 provider 入口就够，
# 整库 keep 会让 R8 无法裁掉未被调用的几千个类（实测 +2MB）。
# ---------------------------------------------------------------------------
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.jcraft.** { *; }
-keep class com.jcraft.jsch.jzlib.** { *; }
-keep class com.jcraft.jsch.juz.** { *; }
-dontwarn com.jcraft.jsch.**

# BouncyCastle 一律不 keep：3.9.7 的 dex 里它本来就被 R8 整块裁掉了（PEM 解析走的是
# jsch 自带的实现），keep 它的 provider 会让包里凭空多出约 1MB 无用类。
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------------------
# Room / DataStore 生成物
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Koin（reified 泛型解析靠类型签名，配合上面的 keepattributes 即可，这里只挡告警）
# ---------------------------------------------------------------------------
-dontwarn org.koin.**

# ---------------------------------------------------------------------------
# 三方库告警噪音（不影响产物，只是让构建日志能看清真问题）
# ---------------------------------------------------------------------------
-dontwarn org.conscrypt.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn com.google.errorprone.annotations.**
