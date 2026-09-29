# 虚拟化引擎模块混淆规则
# 引擎通过反射调用底层 Bcore / 自研 native 实现，相关类名必须保留
-keep class com.dooble.engine.** { *; }
-keep class com.dooble.bcore.** { *; }
-dontwarn com.dooble.bcore.**
