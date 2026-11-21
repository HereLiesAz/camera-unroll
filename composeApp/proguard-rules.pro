# ONNX Runtime calls into these classes from native code by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep line numbers so a crash report can be read.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
