#!/bin/sh
#################################################################
# Shell script invoked by bitcoind for the -walletnotify option #
#################################################################

ADMIN='admin-password'
curl --silent --data 'requestType=tokenExchange' --data 'function=transactionReceived' --data "id=$1" --data-urlencode "adminPassword=$ADMIN" http://localhost:7876/nxt
exit 0

