package org.cache.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.configuration.BasicConfiguration;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;

/**
 * CacheFactory which uses a remote Infinispan server via Hot Rod client.
 * <p>
 * Available java properties:
 * <p>
 * hotrod.properties.file => path to Hot Rod client configuration
 *
 * @author Bela Ban
 * @since x.y
 */
public class HotrodCacheFactory<K, V> implements CacheFactory<K, V> {

    private static final String HR_PROPERTIES = "hotrod.properties.file";
    private static final String DEFAULT_HR_PROPERTIES = "hotrod-factory.properties";
    private RemoteCacheManager remoteCacheManager;
    private ConfigurationBuilderHolder configurationBuilderHolder;

    /**
     * Empty constructor needed for an instance to be created via reflection
     */
    public HotrodCacheFactory() {
    }

    private static ConfigurationBuilderHolder parseConfiguration(String cacheConfig) throws IOException {
        System.out.println("[Hot Rod] using cache configuration: " + cacheConfig);
        ParserRegistry parserRegistry = new ParserRegistry();
        return openInputStream(cacheConfig, is -> parserRegistry.parse(is, null, null));
    }

    private static Configuration parseProperties() throws IOException {
        String hotRodProperties = System.getProperty(HR_PROPERTIES, DEFAULT_HR_PROPERTIES);
        System.out.println("[Hot Rod] using properties: " + hotRodProperties);
        Properties props = new Properties();

        openInputStream(hotRodProperties, is -> {
            props.load(is);
            return null;
        });

        return new ConfigurationBuilder().withProperties(props).build();
    }

    private static <T> T openInputStream(String filenameOrPath, InputStreamFunction<T> f) throws IOException {
        File file = new File(filenameOrPath);
        if (file.exists()) {
            try (FileInputStream is = new FileInputStream(file)) {
                return f.apply(is);
            }
        } else {
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(filenameOrPath)) {
                if (is == null) {
                    throw new FileNotFoundException(filenameOrPath);
                }
                return f.apply(is);
            }
        }
    }

    /**
     * Initializes the remote cache manager
     *
     * @param cacheConfig The cache configuration.
     * @throws Exception If unable to initialize the Hot Rod cache manager.
     */
    public void init(String cacheConfig) throws Exception {
        Configuration config = parseProperties();
        this.remoteCacheManager = new RemoteCacheManager(config);
        this.configurationBuilderHolder = parseConfiguration(cacheConfig);
    }

    public void destroy() {
        RemoteCacheManager cacheManager = this.remoteCacheManager;
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }

    public Cache<K, V> create(String cache_name) {
        BasicConfiguration config = configurationBuilderHolder.getNamedConfigurationBuilders().get(cache_name).build();
        RemoteCache<K, V> cache = remoteCacheManager.administration().getOrCreateCache(cache_name, config);
        return new HotrodCache<>(cache);
    }

    interface InputStreamFunction<T> {
        T apply(InputStream is) throws IOException;
    }
}
