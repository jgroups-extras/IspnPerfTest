package org.cache;

/**
 * Creates instances of {@link Cache}
 * @author Bela Ban
 * @since x.y
 */
public interface CacheFactory<K,V> {

    /**
     * Called after creation to configure the cache factory
     * @param config
     * @throws Exception
     */
    void init(String config) throws Exception;

    /**
     * Called to destroy the cache manager and de-allocate resources created by it
     */
    void destroy();

    /**
     * Creates a new cache
     * @param cache_name the name of the cache to be created. This may correlate with a named cache
     *                   defined in the configuration
     * @return a newly created and configured cache
     */
    Cache<K,V> create(String cache_name);
}
