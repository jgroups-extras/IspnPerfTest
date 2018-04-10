#!/bin/bash

exec `dirname $0`/perf-test.sh -cfg jgroups-tcp.xml -factory org.cache.impl.DummyCacheFactory $*
