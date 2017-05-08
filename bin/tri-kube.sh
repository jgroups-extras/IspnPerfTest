#!/bin/bash

conf_dir=`dirname $0`/../conf

exec `dirname $0`/perf-test.sh -cfg $conf_dir/jgroups-kube.xml -jgroups-cfg $conf_dir/control-kube.xml -factory org.cache.impl.tri.TriCacheFactory $*
