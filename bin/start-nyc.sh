
## Starts an Infinispan instance in NYC, asynchronously backing up to SFC

#!/bin/bash

JAR=ispn-perf-test-1.0.0-SNAPSHOT-jar-with-dependencies.jar
SITE=nyc
CLUSTER=mtest-nyc
CONFIG=mtest.xml
CONTROL_MCAST_PORT=55588
LOCAL_MCAST_PORT=7675

# For TCPPING.initial_hosts in local cluster (if multicasting is disabled)
LOCALJDGHOSTS="127.0.0.1[7601]"

# For TCPPING.initial_hosts in global (bridge) cluster (if multicasting is disabled)
GLOBALJDGHOSTS="127.0.0.1[7800]"

# Tell Infinispan to back up to site sfc
BACKUP=sfc

java -Xmx2G -Xms500m -Djgroups.use.jdk_logger=true \
     -DSITE=${SITE} \
     -DCONTROL_MCAST_PORT=${CONTROL_MCAST_PORT} \
     -DLOCAL_MCAST_PORT=${LOCAL_MCAST_PORT} \
     -DLOCALJDGHOSTS=${LOCALJDGHOSTS} \
     -DGLOBALJDGHOSTS=${GLOBALJDGHOSTS} \
     -DBACKUP=${BACKUP} \
     -jar ${JAR} -cfg ${CONFIG}