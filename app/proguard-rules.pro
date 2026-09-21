# Room
-keep class com.sankatsetu.app.data.** { *; }

# Noise Protocol (reflection-sensitive crypto primitives)
-keep class com.southernstorm.noise.** { *; }

# Cedar / MediaPipe JNI boundaries — added Day 2/3 when those deps land.
