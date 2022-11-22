#!/bin/bash


PWD=QwAo2U6GRxyNPKiZaOCx
ISPN_PERF=IspnPerfTest
ZIP=$ISPN_PERF.zip
DIR=$ISPN_PERF/target/libs
PUB_KEY=$HOME/.ssh/id_rsa.pub

if [ $# -eq 0 ]; then
    echo "prepare.sh HOST"
    exit 1
fi

HOST=$1

if [ -f $ZIP ]; then
   echo "$ZIP exists"
else
   echo "$ZIP does not exist; creating it:"
   rm -f $DIR/hazel* $DIR/coher* $DIR/rocks*
   zip -r9 $ZIP $ISPN_PERF -x $ISPN_PERF/.git/* $ISPN_PERF/.idea/*
fi

echo "-- Uploading artifacts to $HOST"

scp $PUB_KEY root@$HOST



