#!/bin/bash

if [ "$#" -ne 1 ]; then
    echo "Illegal number of parameters: read-only.sh true|false"
    exit 0
fi

DIR=`dirname $0`
threads="$1"
shift

echo "enabling virtual threads: $threads"

$DIR/run.sh -Dorg.infinispan.threads.virtual=$threads -Dvthreads=$threads \
     org.perf.Test -cfg dist-sync-gcp.xml -control-cfg control-gcp.xml \
     -read-percentage 1 $*
