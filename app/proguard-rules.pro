# Audio Cat keeps the release build small and fully offline.
# Media3 / ExoPlayer ships its own consumer rules; nothing extra is required
# for Room or Glide because both publish embedded rules.

# Keep annotations used reflectively by Room-generated code.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Robolectric-free release build: strip all logging below ERROR.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}
