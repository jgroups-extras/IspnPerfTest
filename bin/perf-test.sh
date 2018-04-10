#!/bin/bash


# Author: Bela Ban

DIR=`dirname $0`
POM="$DIR/../pom.xml"

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
#FLAGS="$FLAGS -server -Xms2G -Xmx2G"
FLAGS="$FLAGS -Djava.net.preferIPv4Stack=true"

## Delay asking backup for GET in Infinispan:
FLAGS="$FLAGS -Dinfinispan.stagger.delay=5000"


## Radargun JVM options (comment the above -Xms/-Xmx)
#FLAGS="$FLAGS -Xms16g -Xmx16g -XX:+UseLargePages -XX:+UseG1GC -XX:MaxGCPauseMillis=300 -XX:InitiatingHeapOccupancyPercent=70"

#FLAGS="$FLAGS -verbose:gc -XX:+PrintGCDateStamps -XX:+PrintGCDetails -Xloggc:gc-perf09.log -XX:+PrintTenuringDistribution -XX:+PrintGCApplicationStoppedTime -XX:+PrintGCCause"


## If uncommented and used in prod, license fees may incur
## JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder"

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

#DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8787"
#GC_LOG="-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:$HOME/gclog.log"


## BYTEMAN: uncomment the line below if you want to get timings (avg-send-time, avg-receive-time, avg-delivery-time)
## Run probe timings / timings-reset
#BM="-javaagent:$PT/lib/byteman.jar=script:$CONF/delivery.btm,script:$CONF/send.btm,script:$CONF/requests.btm"

# Uncomment to enable dtrace tracing on the hotspot provider (e.g. lock and method invocation tracing)
#TRACE=-XX:+ExtendedDTraceProbes

export proc_id=$$

#exec java $TRACE $CONFIG -classpath $CP -Dproc_id=${proc_id} $DEBUG $LOG $FLAGS $JMX $JMC $BM org.perf.Test $*

exec mvn -o -f $POM exec:java $FLAGS $JMX $LOG -Dexec.mainClass=org.perf.Test -Dexec.args="$*"
