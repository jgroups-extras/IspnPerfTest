
#!/bin/bash

## Starts a node in the NYC site

site=NYC
backupSites=LON,SFO
mcast_addr=232.2.2.2
mcast_port=52000

flag="-Xms600m -Xmx600m"
flags="$flags -Djava.net.preferIPv4Stack=true -Dlog4j.configuration=file:/home/bela/log4j.properties"
flags="$flags -Dsite=$site -DbackupSites=$backupSites"
flags="$flags -Dmcast_addr=$mcast_addr -Dmcast_port=$mcast_port"
mvn $flags  exec:java -Dexec.mainClass=org.perf.Test