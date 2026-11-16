-keepattributes *Annotation*,InnerClasses,Signature
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep all kotlinx.serialization models & generated serializers across contract, storage, and phaseb
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.galaxy.starshine8.wallpaper.**$$serializer { *; }
-keepclassmembers class com.galaxy.starshine8.wallpaper.** {
    *** Companion;
}
-keepclassmembers class com.galaxy.starshine8.wallpaper.core.contract.** { *; }
-keepclassmembers class com.galaxy.starshine8.wallpaper.core.storage.WallpaperQuotaManager$* { *; }
-keepclassmembers class com.galaxy.starshine8.wallpaper.feature.phaseb.LanExternalComputeAdapter$* { *; }

# Keep ONNX Runtime & ZXing QR generation classes
-keep class ai.onnxruntime.** { *; }
-keep class com.google.zxing.** { *; }
