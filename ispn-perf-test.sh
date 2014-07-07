#!/bin/bash


## Starts a local node using infinispan.xml as Infinispan config and local.xml as JGroups config
## to test this on a single machine you could use aliases on your ethernet interface:
## sudo ifconfig eth0:1 192.168.10.1 netmask 255.255.255.0 up
## sudo ifconfig eth0:2 192.168.10.2 netmask 255.255.255.0 up

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

JG_FLAGS="-Djgroups.bind_addr=$IP_ADDR -Djboss.tcpping.initial_hosts=$IP_ADDR[7800]"
JG_FLAGS="$JG_FLAGS -Djava.net.preferIPv4Stack=true"
FLAGS="-server -Xmx1G -Xms1G"
FLAGS="$FLAGS -XX:CompileThreshold=10000 -XX:+AggressiveHeap -XX:ThreadStackSize=64K -XX:SurvivorRatio=8"
FLAGS="$FLAGS -XX:TargetSurvivorRatio=90 -XX:MaxTenuringThreshold=15"
FLAGS="$FLAGS -Xshare:off"
# JMX="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7777 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
JMX="-Dcom.sun.management.jmxremote"
#EXPERIMENTAL="-XX:+UseFastAccessorMethods -XX:+UseTLAB"

#EXPERIMENTAL="$EXPERIMENTAL -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:+UseBiasedLocking"
EXPERIMENTAL="$EXPERIMENTAL -XX:+EliminateLocks -XX:+UseBiasedLocking"

#EXPERIMENTAL="$EXPERIMENTAL -XX:+AggressiveOpts -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:+UseBiasedLocking -XX:+UseCompressedOops"
#EXPERIMENTAL="$EXPERIMENTAL -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC"

#java -Xrunhprof:cpu=samples,monitor=y,interval=5,lineno=y,thread=y -classpath $CP $LOG $JG_FLAGS $FLAGS $EXPERIMENTAL $JMX  $*

#DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5000"
JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder"

echo "Going to run:"
echo "java -classpath $CP $DEBUG $LOG $JG_FLAGS $FLAGS $EXPERIMENTAL $JMX $JMC org.perf.Test $*"

java -classpath $CP $DEBUG $LOG $JG_FLAGS $FLAGS $EXPERIMENTAL $JMX $JMC org.perf.Test $*
