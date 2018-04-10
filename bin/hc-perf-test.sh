#!/bin/bash

exec `dirname $0`/perf-test.sh -cfg hazelcast.xml -factory hc $*
