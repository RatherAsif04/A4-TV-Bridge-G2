# Keep Android entrypoints & custom views
-keep class dev.a4tvbridge.independent.BridgeActivity { *; }
-keep class dev.a4tvbridge.independent.Glyph { *; }

# Keep Conscrypt TLS provider & JNI bindings
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Keep Kadb ADB client & SPAKE2 wireless pairing
-keep class com.flyfishxu.kadb.** { *; }
-keep class cafe.cryptography.** { *; }
-dontwarn com.flyfishxu.kadb.**

# Keep Bouncy Castle JCA provider & RSA / X.509 / PKCS#8 / PEM classes used by KadbCert
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA1** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA512** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD5** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBE** { *; }
-keep class org.bouncycastle.jcajce.provider.keystore.PKCS12** { *; }
-keep class org.bouncycastle.jcajce.provider.keystore.BC** { *; }
-keep class org.bouncycastle.jcajce.provider.keystore.pkcs12.** { *; }
-keep class org.bouncycastle.jcajce.provider.keystore.bc.** { *; }
-keep class org.bouncycastle.jcajce.provider.drbg.** { *; }
-keep class org.bouncycastle.openssl.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.pkcs.** { *; }
-keep class org.bouncycastle.asn1.x509.** { *; }
-keep class org.bouncycastle.asn1.pkcs.** { *; }
-keep class org.bouncycastle.asn1.x500.** { *; }

# Suppress optional JVM/desktop references from Bouncy Castle, Okio, and Coroutines
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.lang.invoke.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn sun.misc.**
-dontwarn okio.**
