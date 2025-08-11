#!/bin/bash


# Author: Bela Ban

DIR=`dirname $0`
TARGET_DIR=$DIR/../target
LIBS=$TARGET_DIR/libs

if [ ! -d $TARGET_DIR ]; then
   echo "$TARGET_DIR not found; run build.sh first!"
   exit 1
fi

if [ ! -d $LIBS ]; then
  echo "$LIBS not found; run build.sh first!"
  exit 1
fi

if [ -f $HOME/log4j.properties ]; then
    LOG="-Dlog4j.configuration=file:$HOME/log4j.properties"
fi;

if [ -f $HOME/log4j2.xml ]; then
    LOG="$LOG -Dlog4j.configurationFile=$HOME/log4j2.xml"
fi;

if [ -f $HOME/logging.properties ]; then
    LOG="$LOG -Djava.util.logging.config.file=$HOME/logging.properties"
fi;

CP="$TARGET_DIR/classes:$LIBS/*"


### Note: change max heap to 2G on cluster01-08 (physical mem: 4G) !
### On edg-perf, this is OK (physical mem: 32G)
#FLAGS="$FLAGS -server -Xms2G -Xmx2G"
FLAGS="$FLAGS -Djava.net.preferIPv4Stack=true"

java -cp $CP $FLAGS $DEBUG org.perf.GenerateInventoryFile $*