#!/bin/bash


# GC analysis
export GC_FLAGS="-verbose:gc -XX:+PrintGCDetails -XX:+PrintTenuringDistribution -Xloggc:/tmp/ispn-perf-test.gclog"


# Enable flight recorder with our custom profile:
export JMC="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:StartFlightRecording=compress=false,delay=30s,duration=300s,name=$IP_ADDR,filename=/tmp/$IP_ADDR.jfr,settings=profile_2ms.jfc"

./ispn-perf-test.sh $*
