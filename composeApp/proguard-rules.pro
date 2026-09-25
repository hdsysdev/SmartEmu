# BouncyCastle's provider and JMRTD/SCUBA look classes up by name, which R8 can't see
-keep class org.bouncycastle.** { *; }
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.jmrtd.**
-dontwarn net.sf.scuba.**
-dontwarn javax.naming.**
