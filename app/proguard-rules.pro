# Preserve runtime-visible metadata used by Android and third-party libraries.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Android manifest components and library consumer rules are handled by the Android
# Gradle Plugin and dependency AARs. Add targeted keep rules only for proven needs.
