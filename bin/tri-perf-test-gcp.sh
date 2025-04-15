#!/bin/bash

exec `dirname $0`/perf-test.sh -cfg jgroups-gcp.xml -factory org.cache.impl.tri.TriCacheFactory -control-cfg jgroups-gcp.xml $*
