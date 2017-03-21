#!/bin/bash

## Dumps public IP addresses of all EC2 instances starting with a given tag (name), e.g. name=perftest

name=${1:-perftest}


aws ec2 describe-instances \
    --query "Reservations[*].Instances[*].PrivateIpAddress[]" \
    --output=text --filters "Name=tag:Name,Values=$name"