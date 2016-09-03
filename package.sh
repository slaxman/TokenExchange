#!/bin/sh

#########################
# Package TokenExchange #
#########################

if [ -z "$1" ] ; then
  echo "You must specify the version to package"
  exit 1
fi

VERSION="$1"

cd package
rm -R *
mkdir -p addons/lib TokenExchange
cp ../ChangeLog.txt ../LICENSE ../README.md ../token-exchange.properties TokenExchange
cp ../target/TokenExchange-$VERSION.jar addons/lib
cp ../target/lib/* addons/lib
zip -r TokenExchange-$VERSION.zip addons TokenExchange
tar zcf TokenExchange-$VERSION.tar.gz addons TokenExchange
exit 0

