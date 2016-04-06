package org.cache;

import java.util.Set;

/**
 * Generic simple interface for a cache
 * @author Bela Ban
 * @since x.y
 */
public interface Cache<K,V> {
    /**
     * Adds a key and value into a cache
     * @param key the new key
     * @param value the new value
     * @return the previous value associated with key, or null if the implementation doesn't support this
     */
    V put(K key, V value);

    /**
     * Gets the value associated with a given key
     * @param key the key
     * @return the value associated with key, or null if no value is currently associated with the given key
     */
    V get(K key);

    /**
     * Clears the cache. In a replicated cache, this may remove all entries of all cache instances across a cluster
     */
    void clear();

    /**
     * Returns the size of a cache. This may include just the size of the local cache, or of all caches in a cluster
     * @return the number of keys/values in the cache
     */
    int size();

    /**
     * Returns true if the cache has no data in it, false otherwise
     * @return true if the cache has no data, else false
     */
    boolean isEmpty();

    /**
     * Returns the key set of the local cache instance
     * @return the keys of this cache instance
     */
    Set<K> keySet();

}
