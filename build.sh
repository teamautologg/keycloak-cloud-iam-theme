#!/usr/bin/env bash
npm install && npm run build && mvn package && mkdir -p /tmp/theme && cp -v target/*.jar /tmp/theme/
