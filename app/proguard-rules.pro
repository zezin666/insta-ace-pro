# Add project specific ProGuard rules here.

# Protobuf-javalite generated message types are instantiated/reflected by their
# generated companion code after R8 obfuscation. Keep the app-generated schema
# classes and the lite runtime they call.
-keep class com.acegallery.proto.** { *; }
-keep class com.google.protobuf.** { *; }
