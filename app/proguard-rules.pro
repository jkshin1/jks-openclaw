# Keep rules will be tightened alongside the first LiteRT-LM vertical slice.
# LiteRT-LM 0.16.1 has JNI lookups beyond native method names and ships no consumer rules.
# Keep its small Kotlin wrapper intact until release-device mapping validation narrows this.
-keep class com.google.ai.edge.litertlm.** { *; }
