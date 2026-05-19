#!/bin/bash
nohup java -Djava.net.preferIPv4Stack=true -jar anti-cheat-server.jar >> server-app.log 2>&1 &
