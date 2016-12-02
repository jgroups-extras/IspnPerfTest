#!/bin/bash


# Author: Bela Ban

DIR=`dirname $0`
PT="$DIR/../"

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


### Note: change max heap to 2G on cluster01-08 (physical mem: 4G) !
### On edg-perf, this is OK (physical mem: 32G)
FLAGS="$FLAGS -server -Xms2G -Xmx2G"
FLAGS="$FLAGS -Djava.net.preferIPv4Stack=true"
JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder"

## good flags: 112'000 reads/node ispn on edg-perf01-08
# FLAGS="$FLAGS -XX:TLABSize=300k -XX:-ResizeTLAB"
# FLAGS="$FLAGS -XX:+UseParallelGC -XX:GCTimeRatio=99"
# FLAGS="$FLAGS -XX:NewRatio=1"


## G1; optimized for short pauses - remove -Xmx/-Xms!
#FLAGS="$FLAGS -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

## G1; optimized for 1% GC coll - remove -Xmx/-Xms!
#FLAGS="$FLAGS -XX:+UseG1GC -XX:GCTimeRatio=99 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=30"

## CMS; use -Xms/-Xmx
# FLAGS="-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled"

JMX="-Dcom.sun.management.jmxremote"

#java -Xrunhprof:cpu=samples,monitor=y,interval=5,lineno=y,thread=y -classpath $CP $LOG $FLAGS $JMX  $*

#DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5000"

# Enable flight recorder with our custom profile:
#JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:StartFlightRecording=compress=false,delay=30s,duration=300s,name=$IP_ADDR,filename=$IP_ADDR.jfr,settings=profile_2ms.jfc"

## BYTEMAN: uncomment the line below if you want to get delivery times (from reception of a message to delivery to the
## application. Run probe.sh delivery / delivery-reset to get data
#BM="-javaagent:$PT/lib/byteman.jar=script:$PT/conf/delivery.btm"

# Uncomment to enable dtrace tracing on the hotspot provider (e.g. lock and method invocation tracing)
#TRACE=-XX:+ExtendedDTraceProbes

export proc_id=$$

java  $CONFIG -classpath $CP $HAZELCAST -Dproc_id=${proc_id} $DEBUG $LOG $FLAGS $JMX $JMC $BM org.perf.Test $*
