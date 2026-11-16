# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# gomobile rclone binding — JNI / generated Java must keep original names
-keep class com.openminis.rclone.** { *; }
-dontwarn com.openminis.rclone.**

# Rhino (org.mozilla.javascript) references desktop java.beans / javax.lang.model.
# Android R8 treats those as missing and fails minifyRelease (1.33 CI).
-dontwarn java.beans.**
-dontwarn javax.lang.model.**
# Rhino JavaMembers clinit loads javax.lang.model.SourceVersion by name.
# The Android stub lives in src/main/java/javax/lang/model/SourceVersion.java.
# R8 otherwise treats the package as unused and strips it, recreating
# NoClassDefFoundError on the first execute_code / javaToJS.
-keep class javax.lang.model.** { *; }
# org.json resolves from the platform via compileOnly in :core:model —
# no Maven copy ships in the APK, so nothing here needs keeping.
-dontwarn org.json.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
#
# [T-android-rhino-vmbridge-keep] execute_code uses Context.enter() which
# Class.forName()s VMBridge implementations
# (org.mozilla.javascript.jdk18.VMBridge_jdk18, …). R8 sees no Java call
# graph to those types, strips them, and release builds crash on the first
# execute_code with ExceptionInInitializerError / "Failed to create
# VMBridge instance". Keep the whole engine — the sandbox is small next to
# the rest of the APK, and a narrow keep that missed a reflective lookup
# would fail the same way as VAD JNI did in 1.12.
#
# NOTE FOR VERIFICATION: debug builds don't minify. Check against
# assembleRelease (and that mapping.txt still lists org.mozilla.javascript).
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }

# [T-ssh-backend] JSch instantiates kex/cipher/mac/signature/compression
# implementations from its default config table via Class.forName — R8 sees
# no call graph edge to com.jcraft.jsch.jce.* / jgss.* and strips them, so
# every release-build connection dies with ClassNotFoundException while
# debug (no minify) works. Same failure class as the Rhino VMBridge keep
# above. Optional integrations (BouncyCastle, jzlib) are absent by design.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jzlib.**

# [T-dual-source-git] JGit's WindowCache/Monitoring optionally registers an
# MBean when java.lang.management is present. Android has neither that package
# nor javax.management, and R8 fails the release build on the references even
# though the JMX branch is never reached on-device.
# Verified with :app:minifyReleaseWithR8 on 2.0.91.
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# [T-git-panel-r8-jgit] release minify broke the panel's native chain with
# NoClassDefFoundError: r8.A — R8's horizontal class merging synthesized r8.*
# holder classes while surviving references still pointed at the pre-merge
# names, so every JGit entry point (probe/status/logPage) died at class-load.
# JGit is reflection-heavy by design (config parsing, SystemReader, transport
# SPI, annotation reads); partial keeps cannot be audited, so the whole tree
# is kept — same failure class as the Rhino/JSch keeps above.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
# JGit 7.x logs via slf4j-api; providers are ServiceLoader-resolved at runtime
# and absent ones are harmless (silenced logging).
-dontwarn org.slf4j.**

# [T-r8-jgit-desktop-apis] Verified against the JGit 7.8.1 jar shipped in this
# tree (org.eclipse.jgit-7.8.1.202611041320-r.jar):
#   · GC$PidLock.class references java/lang/ProcessHandle (getPID() returns 0
#     when SystemReader.isAndroid(), so the reference is never executed here)
#   · 4 classes reference org/ietf/jgss — HttpAuthMethod$Negotiate,
#     GSSManagerFactory + its two inner factories. Negotiate's static
#     initializer needs JGSS with no Android guard; GSSManagerFactory probes it.
# Neither package exists on Android. Without these exclusions R8 fails the
# release build on missing-class references even though the code paths are
# unreachable on-device. Keep the exemptions scoped to the missing APIs.
-dontwarn java.lang.ProcessHandle
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
