package org.cache.impl;

import org.cache.Cache;
import org.jgroups.JChannel;
import org.jgroups.protocols.TP;
import org.jgroups.raft.blocks.ReplicatedStateMachine;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RaftCache<K, V> implements Cache<K, V> {

    private final ExtendedReplicatedStateMachine<K, V> rsm;

    public RaftCache(JChannel channel) {
        this.rsm = new ExtendedReplicatedStateMachine<>(channel);
        rsm.addRoleChangeListener(role -> System.out.println(channel.address() + ": New role: " + role));
    }

    public boolean isVirtualThreadsEnabled() {
        TP tp = rsm.channel().getProtocolStack().getTransport();
        if (tp == null) return false;

        return tp.useVirtualThreads();
    }

    @Override
    public V put(K key, V value) {
        try {
            return rsm.put(key, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public V get(K key) {
        try {
            return rsm.get(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clear() {
        rsm.clear();
    }

    @Override
    public int size() {
        return rsm.size();
    }

    @Override
    public boolean isEmpty() {
        return rsm.size() == 0;
    }

    @Override
    public Set<K> keySet() {
        return Set.of();
    }

    @Override
    public Map<K, V> getContents() {
        return Map.of();
    }


    private static class ExtendedReplicatedStateMachine<K, V> extends ReplicatedStateMachine<K, V> {

        private final byte CLEAR = 4;

        public ExtendedReplicatedStateMachine(JChannel ch) {
            super(ch);
        }

        public void clear() {
            try {
                raft.set(new byte[] { CLEAR }, 0, 1, repl_timeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) throws Exception {
            if (data[0] == CLEAR) {
                synchronized (map) {
                    map.clear();
                }
                return null;
            }
            return super.apply(data, offset, length, serialize_response);
        }
    }
}
