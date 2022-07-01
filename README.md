[![Build Status](https://github.com/Sciss/Kontakt/workflows/Scala%20CI/badge.svg?branch=main)](https://github.com/Sciss/Kontakt/actions?query=workflow%3A%22Scala+CI%22)

# Kontakt

This repository contains code for an ongoing art project. See
[Research Catalogue](https://www.researchcatalogue.net/view/1154218/1294281)

(C)opyright 2021–2022 by Hanns Holger Rutz. All rights reserved. This project is released under the
[GNU Affero General Public License](https://codeberg.org/sciss/Kontakt/raw/branch/main/LICENSE) v3+ and
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

## annotations

Instead of keeping "all" annotations in the source code in one resource file -- having to update the software
with new annotations -- these are now pulled from the same Mastodon account, by polling private DMs
"addressed to itself", using CW (spoiler) "annot". People can still DM the account, but the confirmation step is to 
toot the annotation  from the Kontakt account to itself.

## updating the table-top version

```
java -cp kontakt.jar de.sciss.kontakt.Window --user USER --pass PASS --verbose --init-delay 0 --shutdown-hour 0 --no-thresh-entries --no-view
```

## rendering xcoax video

To put it together:

    ffmpeg -i frames/frame-%d.jpg -r 25 -crf 19 xcoax-video.mp4

To include fades:

    -vf fade=type=in:start_frame=0:nb_frames=12,fade=type=out:start_frame=51659:nb_frames=34

## change-log

- v0.5.1; add 'evening' shutter time (14-Oct-2021)
- v0.6.0; refactor `Login`; download new annotations from Mastodon DMs (04-Dec-2021)
- v0.7.0; table improvements: accelerate dials, random movements when idle (Jun 2022)
