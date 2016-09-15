#!/bin/sh

#########################
# Package TokenExchange #
#########################

if [ -z "$1" ] ; then
  echo "You must specify the version to package"
  exit 1
fi

VERSION="$1"

if [ ! -d package ] ; then
  mkdir package
fi

cd package
rm -R *
mkdir -p addons/lib TokenExchange
cp ../ChangeLog.txt ../LICENSE ../README.md ../blocknotify.sh ../token-exchange.properties TokenExchange
cp ../target/TokenExchange-$VERSION.jar addons/lib
zip -r TokenExchange-$VERSION.zip addons TokenExchange
dos2unix TokenExchange/ChangeLog.txt TokenExchange/LICENSE TokenExchange/README.md TokenExchange/blocknotify.sh TokenExchange/token-exchange.properties
tar zcf TokenExchange-$VERSION.tar.gz addons TokenExchange
exit 0

