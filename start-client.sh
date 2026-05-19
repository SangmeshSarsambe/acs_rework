#!/bin/bash
nohup java -Djava.net.preferIPv4Stack=true -jar anti-cheat-client.jar >> client-app.log 2>&1 &
