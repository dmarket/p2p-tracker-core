# R8 rules shipped to apps that consume this AAR (proguard.txt).
#
# kotlinx.serialization needs nothing here: kotlinx-serialization-core (a transitive dependency) ships its own
# R8 rules in META-INF/com.android.tools/r8/ (companion and object serializer(), named companions, the runtime
# annotations, R8 full mode), and the app's R8 applies them. This library looks serializers up only through the
# compiler plugin (no serializer(KType), no SerializersModule, no Class.forName), so nothing is resolved
# reflectively. Add a rule only for a lookup those bundled rules do not cover.
