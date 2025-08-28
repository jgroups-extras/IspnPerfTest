package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.jgroups.JChannel;

public class RaftCacheFactory<K, V> implements CacheFactory<K, V> {

  private JChannel channel;

  @Override
  public void init(String config, boolean metricsEnabled, int metricsPort) throws Exception {
    channel = new JChannel(config);

    channel.connect("raft-cluster");
  }

  @Override
  public void destroy() {
    channel.close();
  }

  @Override
  public Cache<K, V> create(String cache_name, String ignore) {
    System.out.println("Creating raft cache: " + cache_name);
    return new RaftCache<>(channel);
  }
}
