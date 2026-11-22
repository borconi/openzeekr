# Consumer ProGuard/R8 rules applied to :app when it shrinks a release build that pulls
# in this module. The AA message models are protobuf-lite, whose parser touches generated
# fields reflectively, so they must survive R8.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class io.aawireless.wirelessaamanipulator.proto.** { *; }
-keep class androidauto.** { *; }
-dontwarn com.google.protobuf.**
