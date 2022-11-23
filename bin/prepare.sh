#!/bin/bash


PWD=QwAo2U6GRxyNPKiZaOCx
ISPN_PERF=IspnPerfTest
ZIP=$HOME/$ISPN_PERF.zip
DIR=$ISPN_PERF/target/libs
KEY=id_rsa.pub
PUB_KEY=$HOME/.ssh/$KEY

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
   zip -r9 $ZIP $ISPN_PERF -x \*.git\* \*.idea\*
fi

echo "-- Uploading artifacts to $HOST"

rm -f $HOME/$ISPN_PERF/target/libs/hazel*
rm -f $HOME/$ISPN_PERF/target/libs/coher*
rm -f $HOME/$ISPN_PERF/target/libs/rocks*
scp $ZIP $PUB_KEY $HOME/infinispan-core*.jar $HOME/$ISPN_PERF/bin/install.sh $HOME/$ISPN_PERF/bin/install2.sh root@$HOST:~/

ssh root@${HOST} chmod +x install.sh


