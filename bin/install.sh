#!/bin/bash

## cisco-e160dp-01.khw2.lab.eng.bos.redhat.com

## Creates a dev env in a cloud based host
## Requires sudo privileges

# change these, e.g. to apt-get
export INSTALL_TOOL=yum ## apt-get
export SSH_KEY=$HOME/.ssh/id_rsa.pub




export ISPN_PERF=IspnPerfTest
export ZIP_FILE=$HOME/$ISPN_PERF.zip
export DIR=$HOME/$ISPN_PERF


#### run as root

echo "-- Configuring host $HOST"

## Install IspnPerfTest from local installation via scp
echo "-- Installing zip/unzip/git/tar/ant/netcat/net-tools"
$INSTALL_TOOL -y install zip unzip git tar ant netcat net-tools

useradd bela -p bela


su - bela
cd
echo $HOME


echo "-- Unzipping $ISPN_PERF.zip"
unzip $ISPN_PERF.zip

echo "--  Installing JGroups"
git clone https://github.com/belaban/JGroups.git
cd JGroups ; ant

export JGROUPS_HOME=$HOME/JGroups
PATH=$PATH:$JGROUPS_HOME/bin

## Install sdkman
echo "-- Installing sdkman"
curl -s "https://get.sdkman.io" | bash

source $HOME/.bashrc

## Install Java 11 and 19

echo "-- Installing JDK 11"
sdk install java 11.0.12-open 
sdk use java 11.0.12-open 


#sdk install java 20.ea.24-open 






