package org.cache.impl;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Timer;
import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.commons.configuration.io.ConfigurationResourceResolvers;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.util.FileLookupFactory;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.metrics.Constants;
import org.infinispan.metrics.impl.MetricsRegistry;
import org.infinispan.metrics.impl.MetricsRegistryImpl;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.jgroups.util.ThreadCreator;
import org.jgroups.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * @author Bela Ban
 * @since x.y
 */
@SuppressWarnings("unused")
@Listener
public class InfinispanCacheFactory<K,V> implements CacheFactory<K,V> {
    protected EmbeddedCacheManager mgr;
    private Thread metricsServerThread;

    /** Empty constructor needed for an instance to be created via reflection */
    public InfinispanCacheFactory() {
    }

    public void init(String config, boolean metricsEnabled, int metricsPort) throws Exception {
        ConfigurationBuilderHolder holder = parseConfiguration(config);
        toggleMetrics(holder, metricsEnabled);
        mgr = new DefaultCacheManager(holder, true);
        mgr.addListener(this);
        if (metricsEnabled) {
            startMetricsServer(metricsPort);
        }
    }

    public void destroy() {
        mgr.stop();
        if (metricsServerThread != null) {
            metricsServerThread.interrupt();
            try {
                metricsServerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Cache<K,V> create(String cache_name, String ignored) {
        org.infinispan.Cache<K,V> cache=mgr.getCache(cache_name);
        // for a put(), we don't need the previous value
        return new InfinispanCache<>(cache);
    }

    @Override
    public LongConsumer metricForOperation(String operation) {
        var registry = metricsRegistry();
        if (!(registry instanceof MetricsRegistryImpl)) {
            return value -> {};
        }
        Timer timer = Timer.builder("requests")
                .description("The benchmark requests")
                .publishPercentileHistogram(true)
                .tags("operation", operation, Constants.NODE_TAG_NAME, mgr.getAddress().toString())
                .register(((MetricsRegistryImpl) registry).registry());
        return value -> timer.record(value, TimeUnit.NANOSECONDS);
    }

    @ViewChanged
    public static void viewChanged(ViewChangedEvent evt) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(evt.getViewId());
        if (evt.getNewMembers() != null) {
            sb.append(" (").append(evt.getNewMembers().size()).append(")");
            sb.append(" [").append(Util.printListWithDelimiter(evt.getNewMembers(), ", ", Util.MAX_LIST_PRINT_SIZE)).append("]");
        }
        System.out.println("** view: " + sb);
    }

    private void startMetricsServer(int metricsPort) {
        var registry = metricsRegistry();
        if (!registry.supportScrape()) {
            return;
        }
        // ISPN 15 and ISPN 16 use an incompatible version of prometheus metrics.
        try {
            System.out.println("Starting Prometheus server in port " + metricsPort);
            HttpServer server = HttpServer.create(new InetSocketAddress(metricsPort), 0);
            server.createContext("/metrics", httpExchange -> {
                // a bit hacky but this should work for both ISPN 15 and ISPN 16
                String response = registry.scrape("text/plain; version=0.0.4; charset=utf-8");
                httpExchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                httpExchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });

            metricsServerThread = ThreadCreator.createThread(server::start, "prometheus", true, true);
            metricsServerThread.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private MetricsRegistry metricsRegistry() {
        return GlobalComponentRegistry.componentOf(mgr, MetricsRegistry.class);
    }

    private static ConfigurationBuilderHolder parseConfiguration(String config) throws IOException {
        try (InputStream is = FileLookupFactory.newInstance().lookupFileStrict(config, Thread.currentThread().getContextClassLoader())) {
            return new ParserRegistry().parse(is, ConfigurationResourceResolvers.DEFAULT, MediaType.APPLICATION_XML);
        }
    }

    private static void toggleMetrics(ConfigurationBuilderHolder holder, boolean enabled) {
        holder.getGlobalConfigurationBuilder()
                .cacheContainer().statistics(enabled)
                .metrics().gauges(enabled).histograms(false).namesAsTags(true);
        holder.getNamedConfigurationBuilders()
                .values()
                .stream()
                .map(ConfigurationBuilder::statistics)
                .forEach(b -> b.enabled(enabled));
    }
}
