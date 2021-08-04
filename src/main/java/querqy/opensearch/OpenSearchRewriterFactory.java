package querqy.opensearch;

import org.opensearch.OpenSearchException;
import org.opensearch.SpecialPermission;
import org.opensearch.index.shard.IndexShard;
import querqy.opensearch.rewriterstore.LoadRewriterConfig;
import querqy.rewrite.RewriterFactory;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Map;

public abstract class OpenSearchRewriterFactory {

    protected final String rewriterId;

    protected OpenSearchRewriterFactory(final String rewriterId) {
        this.rewriterId = rewriterId;
    }

    public abstract void configure(Map<String, Object> config) throws OpenSearchException;

    public abstract List<String> validateConfiguration(Map<String, Object> config);

    public abstract RewriterFactory createRewriterFactory(IndexShard indexShard) throws OpenSearchException;

    public String getRewriterId() {
        return rewriterId;
    }

    public static OpenSearchRewriterFactory loadConfiguredInstance(final LoadRewriterConfig instanceDescription) {

        final String classField = instanceDescription.getRewriterClassName();
        if (classField == null) {
            throw new IllegalArgumentException("Property not found: " + instanceDescription
                    .getConfigMapping().getRewriterClassNameProperty());
        }

        final String className = classField.trim();
        if (className.isEmpty()) {
            throw new IllegalArgumentException("Class name expected in property: " + instanceDescription
                    .getConfigMapping().getRewriterClassNameProperty());
        }

        final Map<String, Object> config = instanceDescription.getConfig();

        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }

        return AccessController.doPrivileged(
                (PrivilegedAction<OpenSearchRewriterFactory>) () -> {

                    final OpenSearchRewriterFactory factory;

                    try {
                        factory = (OpenSearchRewriterFactory) Class.forName(className).getConstructor(String.class)
                                .newInstance(instanceDescription.getRewriterId());

                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }

                    factory.configure(config);
                    return factory;

                });

    }


    public static OpenSearchRewriterFactory loadInstance(final String rewriterId, final Map<String, Object> instanceDesc,
                                                         final String argName) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }


        return AccessController.doPrivileged(
                (PrivilegedAction<OpenSearchRewriterFactory>) () -> {
                    final String classField = (String) instanceDesc.get(argName);
                    if (classField == null) {
                        throw new IllegalArgumentException("Property not found: " + argName);
                    }

                    final String className = classField.trim();
                    if (className.isEmpty()) {
                        throw new IllegalArgumentException("Class name expected in property: " + argName);
                    }


                    try {
                        return (OpenSearchRewriterFactory) Class.forName(className)
                                .getConstructor(String.class).newInstance(rewriterId);
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                });





    }


}
