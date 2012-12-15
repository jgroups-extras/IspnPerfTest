
#!/bin/bash

## Starts a node in the SFO site

site=SFO
backupSites=LON,NYC
mcast_addr=232.3.3.3
mcast_port=53000

flag="-Xms600m -Xmx600m"
flags="$flags -Djava.net.preferIPv4Stack=true -Dlog4j.configuration=file:/home/bela/log4j.properties"
flags="$flags -Dsite=$site -DbackupSites=$backupSites"
flags="$flags -Dmcast_addr=$mcast_addr -Dmcast_port=$mcast_port"
mvn $flags  exec:java -Dexec.mainClass=org.perf.Test