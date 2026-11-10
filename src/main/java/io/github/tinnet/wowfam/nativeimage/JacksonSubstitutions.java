package io.github.tinnet.wowfam.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * Native image only: drops jackson-databind's optional handlers for DOM nodes, {@code javax.xml}
 * types and {@code java.sql} types.
 *
 * <p>wowfam only reads JSON and TOML into trees, so those handlers are never used, but the analysis
 * cannot tell, and keeping them drags {@code java.xml} (about 2 MB of code plus the Xerces/Xalan
 * classes it registers) and {@code java.sql} into the binary. The JVM build is unaffected.
 */
final class JacksonSubstitutions {

  private JacksonSubstitutions() {}

  /** Replaces {@code tools.jackson.databind.ext.OptionalHandlerFactory}: never offers a handler. */
  @TargetClass(className = "tools.jackson.databind.ext.OptionalHandlerFactory")
  static final class TargetOptionalHandlerFactory {

    @Substitute
    public @Nullable ValueSerializer<?> findSerializer(SerializationConfig config, JavaType type) {
      return null;
    }

    @Substitute
    public @Nullable ValueDeserializer<?> findDeserializer(
        DeserializationConfig config, JavaType type) {
      return null;
    }

    @Substitute
    public boolean hasDeserializerFor(Class<?> valueType) {
      return false;
    }
  }
}
