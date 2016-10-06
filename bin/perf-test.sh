#!/bin/bash


# Author: Bela Ban

PT=$HOME/IspnPerfTest
CP=$PT/classes:$PT/lib/*:$PT/conf

if [ -f $HOME/log4j.properties ]; then
    LOG="-Dlog4j.configuration=file:$HOME/log4j.properties"
fi;

if [ -f $HOME/log4j2.xml ]; then
    LOG="$LOG -Dlog4j.configurationFile=$HOME/log4j2.xml"
fi;

if [ -f $HOME/logging.properties ]; then
    LOG="$LOG -Djava.util.logging.config.file=$HOME/logging.properties"
fi;

FLAGS="-server -Xmx1G -Xms500m -Djava.net.preferIPv4Stack=true"
GC="-XX:+UseG1GC"

# Number of HC reader and writer threads: N is 1 selector, N reader threads and N writer threads
HAZELCAST="-Dhazelcast.io.thread.count=200"

# JMX="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7777 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
JMX="-Dcom.sun.management.jmxremote"
PROF="-Xrunhprof:cpu=samples,monitor=y,interval=5,lineno=y,thread=y"

#DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5000"

export proc_id=$$

java -classpath $CP $HAZELCAST -Dproc_id=${proc_id} $DEBUG $LOG $FLAGS $JMX $GC org.perf.Test $*
