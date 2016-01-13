#!/bin/bash


## Starts a local node using infinispan.xml as Infinispan config and local.xml as JGroups config
# Author: Bela Ban


export DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5000"

./ispn-perf-test.sh $*

