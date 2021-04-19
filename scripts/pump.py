#!/usr/bin/env python3
# -*- coding: utf-8 -*-

#Libraries
import time    #https://docs.python.org/fr/3/library/time.html
from adafruit_servokit import ServoKit    #https://circuitpython.readthedocs.io/projects/servokit/en/latest/
import sys

MIN_IMP  =[560, 560, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500]
MAX_IMP  =[2600, 2600, 2500, 2500, 2500, 2500, 2500, 2500, 2500, 2500, 2500, 2500, 2500, 2500, 2500, 2500]

pca = ServoKit(channels=16)

def main():
    ch1   = 7 # 6
    ch2   = 6 # 7
    angDist = 105 # 60
    angRls1 = 60
    angPull1 = angRls1 + angDist
    angRls2 = 120
    angPull2 = angRls2 - angDist
    dur = 0.5
    durStep = 0.01
    pca.frequency = 50 # Hz
    pca.servo[ch1].set_pulse_width_range(MIN_IMP[ch1], MAX_IMP[ch1]) # microseconds
    pca.servo[ch2].set_pulse_width_range(MIN_IMP[ch2], MAX_IMP[ch2]) # microseconds
    # pca.servo[ch1].angle = angPull1 # 0 to 180
    # pca.servo[ch2].angle = angPull2 # 0 to 180
    # time.sleep(dur)
    for j in range(0,angDist,5):
      pca.servo[ch1].angle = angRls1 + j
      pca.servo[ch2].angle = angRls2 - j
      time.sleep(durStep)

    time.sleep(dur)
    pca.servo[ch1].angle = angRls1 # 0 to 180
    pca.servo[ch2].angle = angRls2 # 0 to 180

if __name__ == '__main__':
    main()

