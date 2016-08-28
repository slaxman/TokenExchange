#!/bin/sh
################################################################
# Shell script invoked by bitcoind for the -blocknotify option #
################################################################

ADMIN='admin-password'
curl --silent --data 'requestType=tokenExchange' --data 'function=blockReceived' --data "id=$1" --data-urlencode "adminPassword=$ADMIN" http://localhost:7876/nxt
exit 0

