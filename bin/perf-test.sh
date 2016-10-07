#!/bin/bash


# Author: Bela Ban

PT=$PWD
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

JG_FLAGS="-Djava.net.preferIPv4Stack=true"
FLAGS="-server -Xmx1G -Xms500m"
FLAGS="$FLAGS -XX:CompileThreshold=10000 -XX:+AggressiveHeap -XX:ThreadStackSize=64K -XX:SurvivorRatio=8"
#FLAGS="$FLAGS -XX:TargetSurvivorRatio=90 -XX:MaxTenuringThreshold=15"
FLAGS="$FLAGS -Xshare:off"

# Number of HC reader and writer threads: N is 1 selector, N reader threads and N writer threads
HAZELCAST="-Dhazelcast.io.thread.count=200"

# JMX="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7777 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
JMX="-Dcom.sun.management.jmxremote"
#EXPERIMENTAL="-XX:+UseFastAccessorMethods -XX:+UseTLAB"

#EXPERIMENTAL="$EXPERIMENTAL -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:+UseBiasedLocking"
EXPERIMENTAL="$EXPERIMENTAL -XX:+EliminateLocks -XX:+UseBiasedLocking"

#EXPERIMENTAL="$EXPERIMENTAL -XX:+AggressiveOpts -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:+UseBiasedLocking -XX:+UseCompressedOops"
#EXPERIMENTAL="$EXPERIMENTAL -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC"

#java -Xrunhprof:cpu=samples,monitor=y,interval=5,lineno=y,thread=y -classpath $CP $LOG $JG_FLAGS $FLAGS $EXPERIMENTAL $JMX  $*

#DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5000"

# Enable flight recorder with our custom profile:
#JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:StartFlightRecording=compress=false,delay=30s,duration=300s,name=$IP_ADDR,filename=$IP_ADDR.jfr,settings=profile_2ms.jfc"
#JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder"

export proc_id=$$

java $CONFIG -classpath $CP $HAZELCAST -Dproc_id=${proc_id} $DEBUG $LOG $JG_FLAGS $FLAGS $EXPERIMENTAL $JMX $JMC $GC_FLAGS org.perf.Test $*
