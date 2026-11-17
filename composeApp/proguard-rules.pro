# BouncyCastle's provider and JMRTD/SCUBA look classes up by name, which R8 can't see
-keep class org.bouncycastle.** { *; }
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.jmrtd.**
-dontwarn net.sf.scuba.**
-dontwarn javax.naming.**

# Release builds drop debug and verbose logging: the HCE service logs each APDU and the BAC challenge at debug level.
# Info and above stay, including the PassportEmuEvents event log that `adb logcat -s PassportEmuEvents` follows.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
