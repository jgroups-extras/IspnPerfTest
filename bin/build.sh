#!/bin/bash

mvn -DskipTests=true package dependency:copy-dependencies
