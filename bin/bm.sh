## Uses byteman plus script to run a program

#!/bin/bash
VERSION=4.0.25

if [ $# -lt 2 ];
    then echo "bm.sh byteman-script classname <args>";
         exit 1
fi

SCRIPT=$1
PGM=$2

if [ ! -f $SCRIPT ]; then
   echo "** Script $SCRIPT not found **"
   exit 1
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
LIB=`dirname $SCRIPT_DIR`/target/libs
BM_OPTS="-Dorg.jboss.byteman.compile.to.bytecode=true"
ROOT_DIR=${SCRIPT_DIR}/..

BYTEMAN_JAR=${LIB}/"byteman-${VERSION}.jar"
if [ ! -f $BYTEMAN_JAR ]; then
   echo "** byteman JAR $BYTEMAN_JAR not found **"
   exit 1
fi

shift
shift


$ROOT_DIR/bin/run.sh -javaagent:$BYTEMAN_JAR=script:$SCRIPT $BM_OPTS $PGM $*
