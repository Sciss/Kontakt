#!/bin/bash
cd "$(dirname "$0")"
echo "Kontakt (table)"

sleep 4
xset s off
xset -dpms

java -cp kontakt.jar de.sciss.kontakt.Window --verbose --dials --shutdown-hour 0 --skip-update --init-delay 0 --update-minutes 0 --no-thresh-entries

