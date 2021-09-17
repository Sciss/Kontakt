[![Build Status](https://github.com/Sciss/Kontakt/workflows/Scala%20CI/badge.svg?branch=main)](https://github.com/Sciss/Kontakt/actions?query=workflow%3A%22Scala+CI%22)

# Kontakt

This repository contains code for an ongoing art project. See
[Research Catalogue](https://www.researchcatalogue.net/view/1154218/1294281)

(C)opyright 2021 by Hanns Holger Rutz. All rights reserved. This project is released under the
[GNU Affero General Public License](https://git.iem.at/sciss/WritingSimultan/blob/main/LICENSE) v3+ and
comes with absolutely no warranties.
To contact the author, send an e-mail to `contact at sciss.de`.

The included font for the physical window rendering is [Libre Franklin](https://github.com/impallari/Libre-Franklin)
covered by the SIL Open Font License 1.1.

## building

Builds with sbt against Scala 2.13.
Create executable: `sbt assembly`

## run on the observation Raspberry Pi

    java -Xmx768m -jar kontakt.jar --init-delay 10 -V --user kontakt... --pass ...

Add `--no-shutdown` during testing.

## run on the window Raspberry Pi

    java -Xmx768m -cp kontakt.jar de.sciss.kontakt.Window --init-delay 10 -V --user kontakt... --pass ...

## test runs

`CropPhoto`:
    
    --pre-crop-right 1000 -i /data/projects/Kontakt/materials/snap_210409_154852.jpg -o /data/temp/_killme.jpg -V

`TootPhoto`:

    -u kontakt... -p ... -i /data/temp/_killme.jpg

`ServoTest`

## cache

`Window` caches contents and photos in `~/.cache/kontakt`.

## transfer test

    ffmpeg -i '/data/projects/Kontakt/image_transfer_rsmp/transfer-rsmp-%d.jpg' -r 25 '/data/projects/Kontakt/materials/transfer-rsmp.mp4'

    ffmpeg -i '/data/projects/Kontakt/image_transfer_rsmp/transfer-rsmp-%d.jpg' -r 25 -filter:v "crop=1080:1080:180:180,fade=type=out:start_frame=2225:nb_frames=25" '/data/projects/Kontakt/materials/transfer-rsmp-cr.mp4'

## fix wiring-pi

__Important:__ Wiring-Pi is broken on the Pi 4. The pull up/down resistors cannot be configured.
See https://pi4j.com/1.3/install.html#WiringPi_Native_Library -- one needs to replace the installed versions
with an unofficial one!

    sudo apt remove wiringpi -y
    sudo apt install git-core gcc make
    cd ~/Documents/devel/
    git clone https://github.com/WiringPi/WiringPi --branch master --single-branch wiringpi
    cd wiringpi
    sudo ./build
