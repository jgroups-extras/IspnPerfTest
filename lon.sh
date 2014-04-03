
#!/bin/bash

## Starts a node in the LON site

site=LON
backupSites=SFO,NYC
mcast_addr=232.1.1.1
mcast_port=50000

flag="-Xms600m -Xmx600m"
flags="$flags -Djava.net.preferIPv4Stack=true -Dlog4j.configuration=file:/home/bela/log4j.properties"
flags="$flags -Dsite=$site -DbackupSites=$backupSites"
flags="$flags -Dmcast_addr=$mcast_addr -Dmcast_port=$mcast_port"
java $flags -cp ./classes:./lib/*:./src/main/resources org.perf.Test