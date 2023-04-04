#!/bin/bash


PASS=QwAo2U6GRxyNPKiZaOCx
ISPN_PERF=IspnPerfTest
ZIP=$ISPN_PERF.zip
DIR=$ISPN_PERF/target/libs
KEY=google_compute_engine.pub
PUB_KEY=$HOME/.ssh/$KEY

if [ $# -eq 0 ]; then
    echo "prepare.sh HOST"
    exit 1
fi

HOST=$1
cd $HOME

cd JGroups ; make.sh package ; cd
rm -f $DIR/jgroups-5*.jar
cp JGroups/target/jgroups-5*.jar $DIR/


if [ -f $ZIP ]; then
   echo "$ZIP exists"
else
   echo "$ZIP does not exist; creating it:"
   rm -f $DIR/hazel* $DIR/coher* $DIR/rocks*
   zip -r9 $ZIP $ISPN_PERF -x \*.git\* \*.idea\*
fi

echo "-- Uploading artifacts to $HOST"

scp -i $PUB_KEY $ZIP $PUB_KEY $HOME/$ISPN_PERF/bin/install.sh $HOME/$ISPN_PERF/bin/install2.sh root@$HOST:~/

cd -

ssh root@${HOST} chmod +x install.sh


