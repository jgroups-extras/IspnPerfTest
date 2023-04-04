#!/bin/bash

## cisco-e160dp-01.khw2.lab.eng.bos.redhat.com

## Creates a dev env in a cloud based host
PWD=QwAo2U6GRxyNPKiZaOCx

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
$INSTALL_TOOL -y install zip unzip git tar ant maven netcat net-tools emacs

if [ ! -d ~bela ]; then
  useradd bela -p bela
else
  echo "user bela already exists"
fi

if [ ! -d ~bela/.ssh ]; then
    mkdir ~bela/.ssh
    cat id_rsa.pub >> ~bela/.ssh/authorized_keys
    chown -R bela:bela ~bela/.ssh
else
    echo "~bela/.ssh already exists"
fi

cp $ZIP_FILE ~bela/$ISPN_PERF.zip
cp id_rsa.pub install2.sh ~bela/
chown -R bela:bela ~bela


echo "===== done ====="
echo "ssh into the host (as user) and run ./install2.sh"






