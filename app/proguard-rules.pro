# Starwake release shrinking rules.
#
# The app uses no reflection, serialization or JNI: the only entry point is GameActivity, which AAPT
# keeps automatically from the manifest. Everything else (including :core) may be shrunk, optimized
# and obfuscated freely, so no -keep rules are needed.

# Keep readable line numbers in crash reports (retrace with build/outputs/mapping/release/mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
