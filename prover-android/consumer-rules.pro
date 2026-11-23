# libprovenance_jni.so binds NativeProverBridge's natives by name and creates or calls these by name.
-keep class com.dmarket.p2p.tracker.adapter.notary.NativeProverBridge { native <methods>; }
-keep class com.dmarket.p2p.tracker.adapter.notary.NativeProverException { <init>(int, byte[]); }
-keep class com.dmarket.p2p.tracker.adapter.notary.NativeResponse { <init>(int, long, byte[]); }
-keep interface com.dmarket.p2p.tracker.adapter.notary.NativeProverProgress { *; }
