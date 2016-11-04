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

JG_FLAGS="-Djava.net.preferIPv4Stack=true"
FLAGS="-server -Xms2G -Xmx2G"

## don't use G1 for now
#FLAGS="$FLAGS -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
# FLAGS="-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled"

# JMX="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7777 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
JMX="-Dcom.sun.management.jmxremote"

#java -Xrunhprof:cpu=samples,monitor=y,interval=5,lineno=y,thread=y -classpath $CP $LOG $JG_FLAGS $FLAGS $JMX  $*

#DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5000"

# Enable flight recorder with our custom profile:
#JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:StartFlightRecording=compress=false,delay=30s,duration=300s,name=$IP_ADDR,filename=$IP_ADDR.jfr,settings=profile_2ms.jfc"
#JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder"

export proc_id=$$

java $CONFIG -classpath $CP $HAZELCAST -Dproc_id=${proc_id} $DEBUG $LOG $JG_FLAGS $FLAGS $JMX $JMC $GC_FLAGS org.perf.Test $*
