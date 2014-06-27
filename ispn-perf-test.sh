#!/bin/bash


## Starts a local node using infinispan.xml as Infinispan config and local.xml as JGroups config

PT=$HOME/IspnPerfTest

CP=$PT/classes:$PT/lib/*:$PT/conf
flags="-Xms1G -Xmx1G"
flags="$flags -Djava.net.preferIPv4Stack=true -Dlog4j.configuration=file:/home/bela/log4j.properties"

java $flags -cp $CP org.perf.Test $*