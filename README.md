TokenExchange
=============

TokenExchange is a NRS add-on that automates the process of exchanging Nxt currency for bitcoins.  The add-on watches for transfer transactions of the specified currency.  If the transfer is to the redemption Nxt address, a Bitcoin transaction will be initiated to send the equivalent amount of bitcoins to the bitcoin address specified as a message attached to the transfer transaction.

The nxt/addons/conf/token-exchange.properties configuration file controls the operation of the TokenExchange add-on.  It contains the following fields:
- exchangeRate=nnn.nnn    
    This specifies the value of a single currency unit in bitcoins.  For example, a value of 0.001 indicates that 1 currency unit is equal to 0.001 bitcoins.
 
- currency=xxxxx
    This specifies the 3-5 character Nxt currency code that will be used for the token exchange.

- redemptionAddress=NXT-xxxx-xxxx-xxxx-xxxxx
    This specifies the NXT redemption address.  Currency transfers to this address will be processed by the TokenExchange add-on.
    
- confirmations=n
    This specifies the number of Nxt confirmations before the bitcoins will be sent to the user.
    
- bitcoindAddress=host-name:port
    This specifies the host name and port of the bitcoind server that will be used to send bitcoins to the user.  Since bitcoind no longer supports SSL connections, the bitcoind server should be running on the same system as the NRS server.

    
Installation
============

- Extract the files to the Nxt installation directory

- Add 'addons/lib/*' and 'addons/conf' to the java classpath when starting NRS
    Linux and Mac: -cp classes:lib/*:conf:addons/classes:addons/lib/*:addons/conf
    Windows: -cp classes;lib\*conf;addons\classes;addons\lib\*;addons\conf

- Add 'nxt.addOns=org.ScripterRon.TokenExchange.Main' to nxt.properties
    If you have multiple add-ons, separate each add-on name by a semi-colon

    
Build
=====

I use the Netbeans IDE but any build environment with Maven and the Java compiler available should work.  The documentation is generated from the source code using javadoc.

Here are the steps for a manual build.  You will need to install Maven 3 and Java SE Development Kit 8 if you don't already have them.

  - Create the executable: mvn clean package    
  - [Optional] Create the documentation: mvn javadoc:javadoc    
