# Add project specific ProGuard rules here.

# ONNX Runtime (on-device AI) is called from native code
-keep class ai.onnxruntime.** { *; }
