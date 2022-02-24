#!/bin/bash
cd "$(dirname "$0")"
java -Xmx768m -jar kontakt.jar -V --no-pump --no-toot --no-shutdown --user none --pass none --init-delay 0
