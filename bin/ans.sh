
## Runs ansible-playbook
#!/bin/bash


DIR=`dirname $0`

ANS_DIR=$DIR/../ansible

ansible-playbook --private-key $HOME/.ssh/google_compute_engine -i $ANS_DIR/gcp.yml $*
