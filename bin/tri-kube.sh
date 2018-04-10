#!/bin/bash

conf_dir=`dirname $0`/../conf

exec `dirname $0`/perf-test.sh -cfg jgroups-kube.xml -jgroups-cfg control-kube.xml -factory org.cache.impl.tri.TriCacheFactory $*
