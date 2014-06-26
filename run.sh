
#!/bin/bash

## Starts a local node using infinispan.xml as Infinispan config and local.xml as JGroups config

flag="-Xms600m -Xmx600m"
flags="$flags -Djava.net.preferIPv4Stack=true -Dlog4j.configuration=file:/home/bela/log4j.properties"
flags="$flags -Dmcast_addr=$mcast_addr -Dmcast_port=$mcast_port"
java $flags -cp ./classes:./lib/*:./conf org.perf.Test $*